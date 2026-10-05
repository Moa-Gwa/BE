# Contributing — Moa-Gwa BE

## 브랜치 전략 (git-flow)

| 브랜치 | 용도 | 분기 | 병합 대상 |
| --- | --- | --- | --- |
| `main` | 운영 배포 전용. 릴리스마다 `vX.Y.Z` 태그 | — | — |
| `develop` | 통합 브랜치. 모든 기능이 여기로 모인다 | `main` | — |
| `feature/<이슈번호>-<짧은-설명>` | 기능 개발 (예: `feature/12-crawler-port`) | `develop` | `develop` (PR) |
| `release/vX.Y.Z` | 배포 준비(버전·문서·버그 수정만) | `develop` | `main` + `develop` |
| `hotfix/vX.Y.Z` | 운영 긴급 수정 | `main` | `main` + `develop` |

규칙
- **`main`, `develop`에 직접 커밋/푸시하지 않는다.** 반드시 PR을 통해 병합한다.
- 이슈 없이 시작하는 잡무는 `chore/<설명>`, 문서는 `docs/<설명>`을 쓴다 (분기·병합은 feature와 동일).
- 브랜치는 `develop`의 최신 상태에서 만들고, 병합 후 삭제한다.
- `feature` → `develop` 병합은 **Squash merge**, `release`/`hotfix` → `main` 병합은 **Merge commit**(이력 보존)을 쓴다.
- `main`에 병합한 직후 태그를 만들고 `develop`에도 되돌려 병합한다.

```bash
# 기능 개발
git switch develop && git pull
git switch -c feature/12-crawler-port

# 릴리스
git switch develop && git pull && git switch -c release/v0.1.0
# 병합 후: main에서 git tag -a v0.1.0 -m "v0.1.0" && git push origin v0.1.0
```

## 커밋 컨벤션 (Conventional Commits)

`<type>(<scope>): <설명>` — **type은 영어(Conventional Commits), 설명과 본문은 한국어**로 한 줄 요약 + 필요 시 본문에 변경 이유를 쓴다.

| type | 용도 |
| --- | --- |
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 구조 개선 |
| `docs` | 문서 |
| `test` | 테스트 추가/수정 |
| `chore` | 빌드·설정·의존성 등 |

예: `feat(crawler): 상세 페이지 본문 수집 추가`, `fix(feed): 페이징 중복 항목 수정`

병합 커밋은 `병합: feature/12-crawler-port → develop` 형식을 쓴다. PR 제목·설명과 이슈도 한국어로 작성한다.

## Pull Request

- 대상 브랜치는 `develop` (release/hotfix는 `main`).
- PR 템플릿을 채우고 관련 이슈를 `Closes #번호`로 연결한다.
- 병합 전 최소 1명 리뷰 승인, CI/로컬 검증 통과.
- PR은 작게 유지한다(하나의 이슈 = 하나의 PR).

## 병합 전 로컬 검증

```bash
./mvnw test
```

## 브랜치 보호 설정 (저장소 관리자)

`main`, `develop` 모두 Settings → Branches에서 다음을 켠다.
- Require a pull request before merging (승인 1명 이상)
- Require status checks to pass (CI 추가 후)
- Do not allow force pushes / deletions
