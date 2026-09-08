# deploy-gate admin (frontend)

`deploy-gate` 백엔드의 데이터를 조회하는 **읽기 전용** 관리 화면입니다. React + TypeScript + Vite로 구성되어 있습니다.

## 화면 구성

| 패널 | 내용 | 사용하는 API |
|---|---|---|
| Deployers | 등록된 사용자와 각자가 보유한 claim | `GET /admin/deployers` |
| Stack Policies | 스택별 필요 claim과 승인 레벨 | `GET /admin/stack-policies` |
| Approval History | 최근 승인/거부 감사 로그 | `GET /approval/history?limit=50` |

정책이나 claim을 화면에서 수정하는 기능은 없습니다 (조회 전용).

## 인증

접속하려면 `admin:read` claim을 가진 deployer의 API 토큰이 필요합니다. 토큰은
`sessionStorage`에만 저장되어 탭을 닫으면 사라지며, 모든 요청에
`Authorization: Bearer <token>` 헤더로 전송됩니다. 서버가 401/403을 반환하면 저장된
토큰을 지우고 로그인 화면으로 돌아갑니다.

로컬 데모(`--deploygate.demo-data.enabled=true`)에서는 `dgt_demo_jiye` 또는
`dgt_demo_alice` 토큰을 사용할 수 있습니다.

## 개발 환경 실행

백엔드를 먼저 8080 포트로 띄운 뒤, 프런트엔드 dev server를 실행합니다.

```bash
# 1) 프로젝트 루트에서 백엔드 실행 (데모 데이터 포함)
./gradlew bootRun --args='--deploygate.demo-data.enabled=true'

# 2) 별도 터미널에서 프런트엔드 실행
cd frontend
npm install
npm run dev     # http://localhost:5173
```

`vite.config.ts`가 `/admin`, `/approval` 요청을 `http://localhost:8080`으로 프록시하므로 별도 설정 없이 바로 연동됩니다.

## 빌드

```bash
npm run build   # tsc 타입체크 후 dist/ 에 정적 파일 생성
npm run preview # 빌드 결과 미리보기
```

## 참고

- 백엔드를 다른 오리진에서 직접 호출하는 경우(프록시 미사용), 백엔드의 `deploygate.cors.allowed-origins` 설정에 해당 오리진을 추가해야 합니다. 기본값은 `http://localhost:5173`입니다.
- 백엔드 API 응답 타입은 `src/types.ts`에 정의되어 있으며, 서버의 `deploygate.dto.*` record와 1:1로 대응합니다.
