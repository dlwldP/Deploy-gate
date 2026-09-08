# Deploy-gate

> "누가 어떤 인프라 스택을 배포할 권한이 있는가"를 claims 기반으로 판단하는 배포 승인 게이트 서비스

CI/CD 파이프라인에서 `cdk deploy` 직전에 호출되어, 요청자가 해당 인프라 스택을 배포할 권한이 있는지 판단하는 독립 인가 서비스입니다. GitHub 저장소 write 권한은 "저장소 접근 가능 여부"만 구분할 뿐 "이 스택을 배포해도 되는가"는 구분하지 못합니다 — `deploy-gate`는 그 빈틈을 claims ↔ 정책 매칭이라는 결정론적 규칙만으로 메웁니다. AI나 별도 판단 로직 없이 InfraHub의 claims 기반 인가 모델을 인프라 배포 도메인에 그대로 적용한 프로젝트입니다.

## 해결하는 문제

- 신입 개발자는 테스트 스택(`SmallAppStack`)은 배포해도 되지만, 운영 스택(`ProdAlbStack`)은 배포하면 안 되는 상황
- GitHub 저장소 write 권한과 인프라 배포 권한이 동일시되는 문제
- 배포 승인/거부 이력이 남지 않아 사후 추적이 안 되는 문제

## 동작 흐름

```
GitHub Actions: cdk deploy 직전
        │
        ▼
POST /approval/check
  { "user": "jiye", "stack": "SmallAppStack", "action": "DEPLOY" }
        │
        ▼
[deploy-gate] 요청자 claim(예: "stack:SmallAppStack:deploy")을
              stack_policy와 매칭 → 허용/거부/승인대기 판단
        │
   ┌────┼────────────┐
   ▼    ▼             ▼
 200 OK 403 Forbidden 202 Pending (승인 대기)
   │      │                │
   ▼      ▼                ▼
cdk deploy 계속 진행   실패 처리   승인권자 승인 후 재요청 → 200 OK
```

## 데이터 모델

```
deployer         id, name, claims(예: "stack:SmallAppStack:deploy", "stack:ProdAlbStack:approve")
stack_policy     stack_name, required_claim, approval_level(NONE / SINGLE_APPROVER / DUAL_APPROVER),
                 allowed_actions, approver_claim,
                 approval_ttl_minutes, single_use, require_commit_sha
approval_request deployer_id, stack_name, action, commit_sha,
                 status(PENDING/APPROVED/REJECTED/CONSUMED),
                 required_approvals(생성 시점 스냅샷), created_at, decided_at, expires_at
approval_vote    request_id, approver_id, decision(APPROVE/REJECT)  -- (request_id, approver_id) 유니크
approval_log     감사 로그 — 모든 결정 시점(즉시 승인/거부, 요청 생성, 투표, 최종 결정)에 기록
```

### 승인의 수명

승인은 **한 번 받으면 영원한 통행증이 아닙니다.** 그렇지 않으면 운영 스택 승인을 한 번 받은 뒤
전혀 다른 코드를 무제한으로 배포할 수 있습니다. 세 가지로 범위를 좁힙니다.

| 정책 옵션 | 의미 |
|---|---|
| `require_commit_sha` | check/request가 배포 대상 커밋을 명시해야 함. 없으면 거부(fail-closed) |
| `commit_sha` 바인딩 | 승인은 **그 커밋에만** 유효. 다른 커밋은 자기 승인을 새로 받아야 함 |
| `approval_ttl_minutes` | 승인 후 이 시간이 지나면 만료 (기본 60분) |
| `single_use` | 승인이 배포 1회를 인가하고 소진(`CONSUMED`). 재사용(replay) 시 거부 |
| `approver_claim` | 이 스택을 승인할 수 있는 claim. 미설정 시 `stack:{stack}:approve` 관례를 따름 |
| `allowed_actions` | 이 스택이 응답하는 action 목록(기본 `DEPLOY`). 목록에 없으면 거부 |

### action이 권한을 가른다

`required_claim`에 `{action}` 자리표시자를 쓰면 **작업별로 다른 claim**을 요구합니다.

```
required_claim = "stack:SmallAppStack:{action}"
  DEPLOY  → stack:SmallAppStack:deploy 필요
  DESTROY → stack:SmallAppStack:destroy 필요
```

자리표시자가 없으면 종전처럼 스택 단위의 claim 하나로 모든 action을 통과시킵니다.
action은 대문자로 정규화되므로 `deploy`와 `DEPLOY`가 별개 승인 트랙으로 갈리지 않습니다.

`single_use`가 꺼진 스택은 TTL·커밋 범위 안에서 재시도가 가능해, 파이프라인 재실행이 깨지지 않습니다.
데모 시더 기준 `ProdAlbStack`은 세 옵션을 모두 켠 가장 엄격한 설정입니다.

승인권자는 별도 개념이 아니라 승인 claim을 가진 `Deployer`입니다(기본 `"stack:{stack}:approve"`, 정책의 `approver_claim`으로 팀 단위 claim 지정 가능). 승인 절차는 `ApprovalLevel`에 따라 다형성으로 분기합니다:

```java
public abstract class ApprovalPolicy {
    public abstract ApprovalLevel getSupportedLevel();
    public abstract int requiredApprovals();   // 0 / 1 / 2
    // claim 확인 → 즉시 승인 / 거부 / 승인대기 판단은 상위 클래스가 공통 처리
}
// NoApprovalPolicy      NONE            0인 — claim만 있으면 즉시 승인
// SingleApproverPolicy  SINGLE_APPROVER 1인
// DualApproverPolicy    DUAL_APPROVER   2인 (운영 스택)
```

승인 인원수는 정책 자신이 선언합니다. 서비스가 `level == SINGLE ? 1 : 2` 같은 분기를 들고 있으면
새 레벨이 조용히 남의 정족수를 물려받게 되므로, 레벨 추가가 정책 클래스 하나로 끝나도록 했습니다.
정책은 "즉시 승인/거부/승인대기"만 판단하는 순수 함수이고, 요청 생성·투표 집계·상태 전이는 `ApprovalRequestService`가 담당합니다.

## API

모든 엔드포인트는 `Authorization: Bearer <token>` 인증이 필요합니다 (헬스체크 제외).

| 엔드포인트 | 설명 | 필요 권한 |
|---|---|---|
| `POST /approval/check` | 즉시 판단 필요 시 호출 (CI 파이프라인용). 본문에 `commitSha` 선택 | CI 서비스 토큰 또는 본인 토큰 |
| `POST /approval/request` | 승인 필요 스택은 거부 대신 승인 대기 요청 생성. 본문에 `commitSha` 선택 | CI 서비스 토큰 또는 본인 토큰 |
| `POST /approval/{id}/approve` | 대기 중인 요청 승인 | **개인 토큰만** + `stack:{stack}:approve` |
| `POST /approval/{id}/reject` | 대기 중인 요청 거부 | **개인 토큰만** + `stack:{stack}:approve` |
| `GET /approval/history` | 감사 로그 조회 | `admin:read` |
| `GET /admin/deployers` | deployer와 보유 claim 조회 (읽기 전용) | `admin:read` |
| `GET /admin/stack-policies` | 스택별 정책 조회 (읽기 전용) | `admin:read` |

**인증 모델** — 승인/거부의 신원은 요청 본문이 아니라 토큰에서만 도출합니다 (`approve`/`reject`는 본문 없음). 그렇지 않으면 아무 토큰이나 가진 사람이 `{"approver":"alice"}`로 남을 사칭할 수 있기 때문입니다.

| 자격증명 | 발급 대상 | 신원 증명 | 할 수 있는 일 |
|---|---|---|---|
| 개인 토큰 (`deployer.api_token_hash`) | 사람 | 토큰 보유 | 본인 명의 check/request, 승인·거부 |
| **GitHub OIDC 토큰** | 파이프라인 | **GitHub 서명** | 토큰이 보증한 actor 명의로만 check/request (승인·거부 불가) |
| CI 서비스 토큰 (`deploygate.ci.token`) | 파이프라인 | 공유 시크릿 | 임의 deployer 명의로 check/request (승인·거부 불가) |

파이프라인 자격증명은 승인 투표를 할 수 없어, 유출되더라도 스스로 배포를 승인할 수 없습니다.
요청자 본인도 자기 요청을 승인할 수 없습니다(4-eyes). 토큰은 SHA-256 해시로만 저장됩니다.

**GitHub OIDC** — 공유 시크릿의 문제는 "나는 파이프라인이고 jiye를 대신한다"는 주장 어느 쪽도
증명되지 않는다는 점입니다. GitHub Actions가 발급하는 OIDC 토큰은 GitHub이 서명하며 저장소·actor·
커밋을 담고 있어, 다음이 강제됩니다.

- 남의 이름으로 요청할 수 없음 — `actor` 클레임과 다른 deployer를 지목하면 거부
- 다른 커밋을 승인받을 수 없음 — 토큰의 `sha`가 커밋 바인딩의 근거가 되고, 본문이 다른 값을 보내면 거부
- 허용된 저장소·audience에서 발급된 토큰만 인정

```yaml
# 설정하면 활성화. 비워두면 OIDC는 꺼지고 기존 CI 토큰만 동작합니다.
deploygate.ci.oidc.issuer: https://token.actions.githubusercontent.com
deploygate.ci.oidc.jwk-set-uri: https://token.actions.githubusercontent.com/.well-known/jwks
deploygate.ci.oidc.audience: deploy-gate
deploygate.ci.oidc.allowed-repositories: owner/repo
```

OIDC를 쓰려면 deployer의 `name`이 GitHub 로그인과 같아야 합니다(actor 클레임과 매칭).

## 기술 스택

| 영역 | 기술 |
|---|---|
| 서비스 | Spring Boot, Spring Security (Bearer 토큰 + GitHub OIDC 검증, SHA-256 해시) |
| 인가 모델 | Claims 기반 (InfraHub 패턴 재사용) |
| 연동 대상 | AWS CDK(Java) 스택, GitHub Actions |
| 모니터링 | Spring Boot Actuator (health/info/metrics, readiness probe) |
| DB | SQLite (파일 기반, 별도 DB 서버 없음) |
| 관리 화면 | React + TypeScript + Vite (조회 전용, `frontend/`) |

## 프로젝트 구조

```
deploy-gate/
├── src/main/java/deploygate/
│   ├── entity/       # Deployer, StackPolicy, ApprovalRequest, ApprovalVote, ApprovalLog
│   ├── dao/          # Spring Data JPA 리포지토리
│   ├── policy/       # ApprovalPolicy 및 No/Single/DualApproverPolicy
│   ├── service/      # ApprovalCheckService, ApprovalRequestService, ApprovalLogService
│   ├── controller/   # /approval/*, /admin/*
│   ├── dto/          # 요청/응답 record
│   ├── validation/   # 커스텀 예외 + GlobalExceptionHandler
│   ├── security/     # 토큰 인증 필터 + Spring Security 설정
│   └── config/       # DemoDataSeeder
├── frontend/         # 조회 전용 관리 화면 — 자세한 내용은 frontend/README.md
└── .github/workflows/  # 자체 CI(build.yml) + 연동 예시(deploy-example.yml)
```

## 구현 현황

| 단계 | 범위 | 상태 |
|---|---|---|
| P1 | claim ↔ 정책 매칭 즉시 허용/거부, `POST /approval/check` | ✅ |
| P2 | Single/Dual Approver, 승인 대기 요청·투표, 감사 로그 | ✅ |
| P3 | Actuator·SQLite 영속화, 관리 화면, Bearer 인증 + 4-eyes | ✅ |
| P3 스코프 아웃 | 멀티 프로젝트용 범용 라이브러리화 | ⏸️ 보류 |

**배포 상태**: 현재 **미배포**, 로컬/CI 검증만 유지합니다. 프로덕션 배포(토큰 실발급, 데모 시더 비활성화 등)는 별도 작업입니다.

## 다음 개발 포인트

- **승인 대기 목록 조회 + 프론트 승인/거부 액션** — 지금은 대기 중인 요청을 목록으로 볼 방법이 없어, 승인권자가 요청 ID를 CI 로그에서 찾아 curl로 승인해야 함. `GET /approval/pending` + 관리 화면 액션 버튼을 추가하면 실제로 승인권자가 쓸 수 있는 도구가 됨 (최우선)
- **Deployer/토큰 프로비저닝 API** — 현재 데모 시더 외에는 deployer 생성·토큰 발급 경로가 없음. 실사용 온보딩을 위해 `POST /admin/deployers` + 토큰 재발급/회수 엔드포인트 필요
- **본인 이력 셀프 조회** — `admin:read` 없이는 자기 승인 이력도 못 봄. 개인 토큰으로 자기 이름 필터 조회 정도는 허용해도 안전
- **감사 로그 export(CSV/JSON)** — 컴플라이언스·사후 감사용
- **승인 사유 기록** — 지금은 "누가 승인했는지"만 남고 "왜"는 안 남음. approve/reject에 코멘트 필드 추가
- **토큰 수명주기** — 회전·폐기, 퇴사자 비활성화(`enabled` 플래그) 개념이 아직 없음
- **관측성** — 승인 대기 건수 gauge, 승인까지 걸린 시간 timer 등 비즈니스 메트릭
