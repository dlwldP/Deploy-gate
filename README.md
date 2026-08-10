# Deploy-gate

> "누가 어떤 인프라 스택을 배포할 권한이 있는가"를 claims 기반으로 판단하는 배포 승인 게이트 서비스

## 개요

`deploy-gate`는 CI/CD 파이프라인에서 `cdk deploy` 직전에 호출되어, 요청자가 해당 인프라 스택을 배포할 권한이 있는지 판단하는 독립 인가 서비스입니다.

GitHub 저장소 권한(collaborator, write 등)은 "이 저장소에 접근 가능한가"만 구분할 뿐, "이 특정 인프라 스택을 배포해도 되는가"는 구분하지 못합니다. `deploy-gate`는 이 사이의 빈틈을 claims 기반 인가 모델로 메웁니다. AI/판단 로직 없이, claim과 정책 테이블을 매칭하는 순수 규칙 기반 서비스입니다.

## 배경

- **InfraHub와의 연결점**: InfraHub에서 다룬 claims 기반 인가 모델(웹 애플리케이션 리소스별 권한 관리)을, 대상만 "웹 리소스"에서 "인프라 스택"으로 바꿔 CI/CD 파이프라인에 그대로 적용
- IaC(AWS CDK)와 CI/CD(GitHub Actions)를 학습하며 발견한 문제: 배포 자동화 파이프라인은 "누구나 실행 권한만 있으면 모든 스택을 배포할 수 있다"는 구조적 허점을 갖기 쉬움
- AI 에이전트나 봇 없이, **결정론적 규칙(claim ↔ policy 매칭)만으로 완결되는 단일 도메인 문제**로 설계

## 해결하는 문제

- 신입/주니어 개발자는 테스트용 스택(`SmallAppStack`)은 배포 가능해도, 운영 스택(`ProdAlbStack`)은 배포하면 안 되는 상황
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
[deploy-gate] — Spring Boot
  1. 요청자의 claim 조회 (예: "stack:SmallAppStack:deploy")
  2. stack_policy 테이블과 매칭 → 허용/거부/승인대기 판단
        │
   ┌────┼────────────┐
   ▼    ▼             ▼
 200 OK 403 Forbidden 202 Pending (승인 대기)
   │      │                │
   ▼      ▼                ▼
cdk deploy 워크플로우      승인권자 승인 시
 계속 진행  실패 처리       재요청 → 200 OK
```

## 데이터 모델

```
deployer
  - id, name
  - claims: ["stack:SmallAppStack:deploy", "stack:ProdAlbStack:approve", ...]
    # "{action}" 뿐 아니라 승인권자 여부도 "stack:{stack}:approve" claim으로 동일하게 표현

stack_policy
  - stack_name        # 예: "ProdAlbStack"
  - required_claim     # 예: "stack:ProdAlbStack:deploy"
  - approval_level     # NONE / SINGLE_APPROVER / DUAL_APPROVER

approval_request        # 승인 대기 중인 요청 (approval_level > NONE 인 경우에만 생성)
  - deployer_id, stack_name, action
  - status              # PENDING / APPROVED / REJECTED
  - required_approvals  # 생성 시점의 필요 승인 수 스냅샷 (SINGLE=1, DUAL=2)
  - created_at, decided_at

approval_vote            # approval_request 하나에 대한 승인권자별 투표
  - request_id, approver_id, decision(APPROVE/REJECT), decided_at
  - (request_id, approver_id) 유니크 — 동일 승인권자의 중복 투표 방지

approval_log             # 감사 로그 — 모든 실제 결정 시점(즉시 승인/거부, 요청 생성, 투표, 최종 승인/거부)에 기록
  - deployer_name, stack_name, action, result, decided_by, decided_at, request_id
```

정책(`approval_level`)에 따라 승인 절차가 달라지도록, OOP 다형성으로 설계:

```java
public abstract class ApprovalPolicy {
    public abstract ApprovalLevel getSupportedLevel();
    public abstract ApprovalResult evaluate(Deployer deployer, StackPolicy stackPolicy);
}

public class NoApprovalPolicy extends ApprovalPolicy { /* claim만 있으면 즉시 승인 */ }
public class SingleApproverPolicy extends ApprovalPolicy { /* claim 확인 후 승인권자 1인 필요(pending) */ }
public class DualApproverPolicy extends ApprovalPolicy { /* claim 확인 후 승인권자 2인 필요(pending, 운영 스택) */ }
```

정책 자체는 "claim 보유 여부 → 즉시 승인/거부/승인대기(pending)"만 판단하는 순수 함수이고, 승인 대기 요청의 생성·투표 집계·상태 전이는 `ApprovalRequestService`가 담당합니다.

## API

모든 엔드포인트는 `Authorization: Bearer <token>` 인증이 필요합니다 (헬스체크 제외).

| 엔드포인트 | 설명 | 필요 권한 |
|---|---|---|
| `POST /approval/check` | 즉시 판단 필요 시 호출 (CI 파이프라인에서 사용) | CI 서비스 토큰 또는 본인 토큰 |
| `POST /approval/request` | 승인 레벨이 높은 스택은 즉시 거부 대신 승인 대기 상태 생성 | CI 서비스 토큰 또는 본인 토큰 |
| `POST /approval/{id}/approve` | 승인권자가 대기 중인 요청 승인 | **개인 토큰만** + `stack:{stack}:approve` |
| `POST /approval/{id}/reject` | 승인권자가 대기 중인 요청 거부 | **개인 토큰만** + `stack:{stack}:approve` |
| `GET /approval/history` | 감사 로그 조회 (누가 언제 무엇을 승인/거부했는지) | `admin:read` |
| `GET /admin/deployers` | 등록된 deployer와 보유 claim 조회 (관리 화면용, 읽기 전용) | `admin:read` |
| `GET /admin/stack-policies` | 스택별 정책(필요 claim, 승인 레벨) 조회 (관리 화면용, 읽기 전용) | `admin:read` |

### 인증 모델

승인/거부의 **신원은 요청 본문이 아니라 토큰에서 도출**합니다. `approve`/`reject`는 본문이 없고,
누가 승인했는지는 오직 토큰으로만 결정됩니다 — 그렇지 않으면 아무 토큰이나 가진 사람이
`{"approver":"alice"}`로 남의 이름을 사칭할 수 있습니다.

| 토큰 종류 | 발급 대상 | 할 수 있는 일 |
|---|---|---|
| 개인 토큰 (`deployer.api_token_hash`) | 사람 | 본인 명의의 check/request, 승인·거부 |
| CI 서비스 토큰 (`deploygate.ci.token`) | 파이프라인 | 임의 deployer 명의로 check/request **(승인·거부는 불가)** |

CI 서비스 토큰이 승인 투표를 할 수 없는 것이 핵심입니다 — 파이프라인 시크릿이 유출돼도
운영 스택 배포를 스스로 승인할 수 없습니다. 여기에 더해 **요청자 본인은 자기 요청을 승인할 수
없습니다**(4-eyes). 토큰은 SHA-256 해시로만 저장되며 평문은 보관하지 않습니다.

## 기술 스택

| 영역 | 기술 |
|---|---|
| 서비스 | Spring Boot |
| 인가 모델 | Claims 기반 (InfraHub 패턴 재사용) |
| 인프라 대상 | AWS CDK (Java) 로 정의된 스택 |
| 파이프라인 연동 | GitHub Actions |
| 인증 | Bearer API 토큰 (SHA-256 해시 저장, Spring Security) |
| 헬스체크/모니터링 | Spring Boot Actuator (health, info, metrics, readiness probe) |
| DB | SQLite (파일 기반 영속화, 별도 DB 서버 없이 단일 인스턴스로 운영) |
| 관리 화면 | React + TypeScript + Vite (조회 전용, `frontend/`) |

## 프로젝트 구조

```
deploy-gate/
├── src/main/java/deploygate/
│   ├── entity/       # Deployer, StackPolicy, ApprovalRequest, ApprovalVote, ApprovalLog 등 JPA 엔티티
│   ├── dao/          # Spring Data JPA 리포지토리
│   ├── policy/       # ApprovalPolicy 및 하위 구현체(No/Single/DualApproverPolicy)
│   ├── service/      # ApprovalCheckService, ApprovalRequestService, ApprovalLogService
│   ├── controller/   # /approval/* 컨트롤러
│   ├── dto/          # 요청/응답 record
│   ├── validation/   # 커스텀 예외 + GlobalExceptionHandler
│   ├── security/     # 토큰 인증 필터 + Spring Security 설정
│   └── config/       # DemoDataSeeder 등 초기 설정
├── src/main/resources/application.yml   # SQLite + Actuator 설정
├── src/test/resources/application.yml   # 테스트 전용 H2 인메모리 설정(컨텍스트별 격리)
├── frontend/             # 조회 전용 관리 화면 (React + TS + Vite) — 자세한 내용은 frontend/README.md
└── .github/workflows/    # deploy-gate 자체 CI(build.yml) + 연동 예시 워크플로우(deploy-example.yml)
```

(최초 설계 시 구상했던 `domain/`·`api/` 패키지명은 구현 과정에서 각각 `entity/`·`controller/`로 정착했습니다.)

## 상태 및 MVP 범위

✅ P1(MVP) · P2 · P3 완료 (배포는 보류 — 아래 "배포 상태" 참고)

### P1 — MVP 핵심 ✅
- `deployer` / `stack_policy` 데이터 모델 및 claim 매칭 로직
- `POST /approval/check` — 승인 없이 claim 매칭만으로 허용/거부 판단 (`NoApprovalPolicy`만 지원)
- GitHub Actions 연동 예시 워크플로우 (`cdk deploy` 전 호출)

### P2 — 2차 ✅
- `SingleApproverPolicy`, `DualApproverPolicy` 등 승인 레벨 확장
- `POST /approval/request` + `approve`/`reject` 승인 대기 흐름
- `approval_log` 감사 로그 및 `GET /approval/history` 조회 API

### P3 — 확장 ✅
- ✅ Actuator 기반 헬스체크 및 운영 모니터링 연동 (health/info/metrics), SQLite 영속화
- ✅ 정책/claim 현황을 확인할 수 있는 관리 화면 (`frontend/`, React + TS) — 조회 전용으로 구현, 화면에서의 정책 수정(CRUD)은 미지원
- ✅ Bearer 토큰 인증 및 4-eyes(자기 승인 차단) 적용
- ⏸️ 다른 CDK 스택/멀티 프로젝트로 확장 가능한 범용 라이브러리화 — 검토 후 스코프 아웃(보류), P3 완료 조건에서 제외

### 배포 상태

현재는 **미배포** 상태이며, 로컬/CI 환경에서의 빌드·테스트 검증만 유지합니다. 프로덕션 배포(토큰 실발급, 데모 시더 비활성화, 인프라 구성 등)는 별도 작업으로 남겨둡니다.

## 왜 이 프로젝트인가

- InfraHub의 claims 기반 인가 모델을 "웹 리소스 인가"에서 "인프라 배포 인가"로 확장 — 하나의 인가 도메인 전문성을 두 프로젝트로 증명
- AI/봇 없이 순수 규칙 기반 설계로, 명확한 단일 도메인 문제에 집중
- OOP 상속/다형성(`ApprovalPolicy` 계층)을 실제 인가 정책 설계에 적용해보는 실습
