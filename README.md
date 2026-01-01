# AI 기반 로그 분석 시스템 - 전체 통합 가이드

Spring Boot에서 수집한 로그를 AI로 분석하고 챗봇으로 질의할 수 있는 완전한 시스템입니다.

## 📦 프로젝트 구조

```
/mnt/c/Project/prototype/
├── infrastructure/              # Docker Compose (OpenSearch, Kafka)
├── log-collector-library/       # Spring Boot 로그 수집 라이브러리 (Java)
├── test-spring-app/             # 테스트용 Spring Boot 앱 (Java)
└── log-analysis-api/            # AI 분석 & 챗봇 API (Python FastAPI)
```

## 🎯 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                      TEST SPRING APP                             │
│                   (Port: 8080)                                   │
│                                                                  │
│  @LogCollect 어노테이션                                          │
│       ↓                                                          │
│  AOP 로그 수집                                                   │
│       ↓                                                          │
│  Kafka Producer                                                  │
└────────────┬────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                     INFRASTRUCTURE                               │
│                                                                  │
│  Kafka (9092) ────► OpenSearch (9200)                           │
│                                                                  │
└────────────┬────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                  LOG ANALYSIS API                                │
│                   (Port: 8000)                                   │
│                                                                  │
│  1. Kafka Consumer → 로그 수신                                  │
│  2. OpenAI Embedding → 벡터 생성                                │
│  3. OpenSearch 저장                                             │
│  4. API:                                                         │
│     - GET /api/v1/logs/{id}/analysis  (로그 분석)              │
│     - POST /api/v1/chatbot/ask        (챗봇 질문)              │
└─────────────────────────────────────────────────────────────────┘
```

## 🚀 전체 시스템 실행 가이드

### 전체 가이드

**상세 통합 테스트 가이드**: [INTEGRATION_TEST_GUIDE.md](INTEGRATION_TEST_GUIDE.md) 📖

---

### Step 1: Infrastructure 실행 (5분)

```bash
cd prototype/infrastructure
bash scripts/setup.sh
```

**확인**:
- OpenSearch: http://localhost:9200
- OpenSearch Dashboards: http://localhost:5601
- Kafka: localhost:9092

### Step 2: Log Collector Library 빌드 (2분)

```bash
cd ../log-collector-library
./gradlew clean build

# 빌드 결과 확인
ls -lh build/libs/log-collector-library-1.0.0.jar
```

### Step 3: Log Analysis API 준비 (3분)

```bash
cd ../log-analysis-api

# 환경 변수 설정
cp .env.example .env
# .env 파일을 열어서 OPENAI_API_KEY 설정

# Python 가상환경
python -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate

# 의존성 설치
pip install -r requirements.txt

# OpenSearch 인덱스 생성
python scripts/create_indices.py

# 연결 테스트
python scripts/test_connection.py
```

### Step 4: Test Spring App 실행 (2분)

```bash
cd ../test-spring-app
./gradlew bootRun
```

서버 시작 확인:
```
✅ Log Collector Auto-Configuration activated
🚀 Starting test-spring-app
```

### Step 5: Log Analysis API 실행 (1분)

**새 터미널**에서:

```bash
cd log-analysis-api
source venv/bin/activate
uvicorn app.main:app --reload --port 8000
```

서버 시작 확인:
```
🚀 Starting log-analysis-api v1.0.0
✅ Kafka consumer task started
```

## ✅ 전체 통합 테스트

### 테스트 1: 로그 수집 → OpenSearch 저장

**1단계**: test-spring-app에서 API 호출

```bash
# 정상 동작 - INFO 로그
curl "http://localhost:8080/api/test/success?name=Alice"
```

**2단계**: log-analysis-api 로그 확인

터미널에서 다음 메시지 확인:
```
📨 Processing log: abc123...
✅ Log saved to OpenSearch: abc123 -> logs-2024-01
```

**3단계**: OpenSearch에서 로그 확인

```bash
curl "http://localhost:9200/logs-*/_search?pretty&size=1"
```

### 테스트 2: 에러 로그 + AI 분석

**1단계**: 에러 발생

```bash
curl "http://localhost:8080/api/test/error"
```

**2단계**: OpenSearch에서 로그 ID 확인

```bash
curl "http://localhost:9200/logs-*/_search?q=level:ERROR&pretty" | grep '"log_id"'
```

출력 예시:
```json
"log_id": "550e8400-e29b-41d4-a716-446655440000"
```

**3단계**: AI 분석 API 호출

```bash
# 위에서 확인한 log_id 사용
curl "http://localhost:8000/api/v1/logs/550e8400-e29b-41d4-a716-446655440000/analysis" | jq
```

**기대 결과**:
```json
{
  "log_id": "550e8400-...",
  "analysis": {
    "summary": "Intentional test error in TestService.processError()",
    "error_cause": "RuntimeException thrown for testing purpose",
    "solution": "This is a test error. Remove or modify the test code.",
    "tags": ["RuntimeException", "TestService", "test"],
    "analyzed_at": "2024-01-15T10:30:00.000Z"
  },
  "from_cache": false
}
```

### 테스트 3: 유사도 캐싱 확인

**1단계**: 같은 에러 다시 발생

```bash
curl "http://localhost:8080/api/test/error"
```

**2단계**: 새 로그 ID로 분석 요청

```bash
# 새로운 log_id 사용
curl "http://localhost:8000/api/v1/logs/NEW_LOG_ID/analysis" | jq
```

**기대 결과**:
```json
{
  "from_cache": true,
  "similar_log_id": "550e8400-..."  // 이전 로그 ID
}
```

→ **LLM을 호출하지 않고 유사한 로그의 분석 결과를 재사용!**

### 테스트 4: 챗봇 질문

**1단계**: 여러 로그 생성

```bash
for i in {1..5}; do
  curl "http://localhost:8080/api/test/success?name=User$i"
  sleep 1
done

curl "http://localhost:8080/api/test/error"
curl "http://localhost:8080/api/test/slow?delayMs=2000"
```

**2단계**: 챗봇에게 질문

```bash
curl -X POST "http://localhost:8000/api/v1/chatbot/ask" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "최근에 어떤 로그들이 발생했어?"
  }' | jq
```

**기대 결과**:
```json
{
  "answer": "최근 test-spring-app 서비스에서 다음과 같은 로그들이 발생했습니다:\n\n1. User1~User5에 대한 정상 처리 로그 (INFO 레벨)\n2. TestService.processError()에서 의도적인 테스트 에러 (ERROR 레벨)\n3. 2초 지연 작업 완료 (INFO 레벨)\n\n대부분 정상 동작 로그이며, 1건의 테스트 에러가 있습니다.",
  "from_cache": false,
  "related_logs": [...]
}
```

### 테스트 5: 챗봇 QA 캐시 확인

**1단계**: 같은 질문 다시 하기

```bash
curl -X POST "http://localhost:8000/api/v1/chatbot/ask" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "최근에 어떤 로그들이 발생했어?"
  }' | jq '.from_cache'
```

**기대 결과**:
```json
true
```

→ **LLM을 호출하지 않고 캐시된 답변 반환!**

## 📊 성능 확인

### 토큰 소비 비교

**캐시 미스 (첫 번째 질문)**:
- Embedding: 200 토큰
- LLM 입력: 1,500 토큰
- LLM 출력: 300 토큰
- **총 비용**: ~$0.01

**캐시 히트 (두 번째 질문)**:
- Embedding: 200 토큰
- LLM 호출 없음
- **총 비용**: ~$0.00003

→ **약 300배 비용 절감!**

### 응답 속도 비교

```bash
# 캐시 미스
time curl -X POST "http://localhost:8000/api/v1/chatbot/ask" \
  -H "Content-Type: application/json" \
  -d '{"question": "새로운 질문"}'

# 약 3-5초

# 캐시 히트
time curl -X POST "http://localhost:8000/api/v1/chatbot/ask" \
  -H "Content-Type: application/json" \
  -d '{"question": "같은 질문"}'

# 약 0.5초
```

## 🎬 데모 시나리오

### 시나리오: 프로덕션 환경 에러 분석

**1. 에러 발생 시뮬레이션**

```bash
# 10개의 에러 로그 생성
for i in {1..10}; do
  curl "http://localhost:8080/api/test/error"
  sleep 0.5
done
```

**2. 개발자가 로그 확인**

```bash
# OpenSearch에서 에러 로그 검색
curl "http://localhost:9200/logs-*/_search?q=level:ERROR&pretty&size=1" \
  | grep -A 5 '"log_id"'
```

**3. AI 분석 요청**

```bash
LOG_ID="<위에서 확인한 log_id>"
curl "http://localhost:8000/api/v1/logs/$LOG_ID/analysis" | jq '
{
  summary: .analysis.summary,
  cause: .analysis.error_cause,
  solution: .analysis.solution
}'
```

**4. 챗봇으로 추가 정보 질의**

```bash
curl -X POST "http://localhost:8000/api/v1/chatbot/ask" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "이 에러가 얼마나 자주 발생했어?",
    "filters": {"level": "ERROR"}
  }' | jq '.answer'
```

**5. 해결 후 확인**

```bash
# 정상 동작 로그 생성
curl "http://localhost:8080/api/test/success?name=Fixed"

# 챗봇으로 확인
curl -X POST "http://localhost:8000/api/v1/chatbot/ask" \
  -H "Content-Type: application/json" \
  -d '{"question": "에러가 해결됐어?"}' \
  | jq '.answer'
```

## 🛠️ 유용한 명령어 모음

### 전체 로그 확인

```bash
# test-spring-app 로그
cd test-spring-app
./gradlew bootRun 2>&1 | grep "Log sent to Kafka"

# log-analysis-api 로그
cd log-analysis-api
# 실행 중인 터미널 확인

# Kafka 메시지
cd infrastructure
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic application-logs \
  --from-beginning \
  --max-messages 10

# OpenSearch 로그
curl "http://localhost:9200/logs-*/_count?pretty"
```

### 데이터 초기화

```bash
# OpenSearch 인덱스 삭제
curl -X DELETE "http://localhost:9200/logs-*"
curl -X DELETE "http://localhost:9200/qa-cache"

# 인덱스 재생성
cd log-analysis-api
python scripts/create_indices.py
```

### 시스템 상태 확인

```bash
# 모든 서비스 헬스 체크
echo "=== test-spring-app ===" && curl -s http://localhost:8080/api/test/health | jq
echo "=== log-analysis-api ===" && curl -s http://localhost:8000/api/v1/health | jq
echo "=== OpenSearch ===" && curl -s http://localhost:9200/_cluster/health | jq
echo "=== Kafka ===" && cd infrastructure && docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

## 🐛 트러블슈팅

### 문제: Kafka 연결 실패

**증상**:
```
Error sending log to Kafka: Connection refused
```

**해결**:
```bash
cd infrastructure
docker-compose ps
docker-compose logs kafka
```

### 문제: OpenSearch 저장 실패

**증상**:
```
Failed to index log: ConnectionError
```

**해결**:
```bash
curl http://localhost:9200/_cluster/health?pretty
# status가 "green" 또는 "yellow"인지 확인
```

### 문제: OpenAI API 에러

**증상**:
```
AuthenticationError: Invalid API key
```

**해결**:
```bash
cd log-analysis-api
cat .env | grep OPENAI_API_KEY
# 키가 올바른지 확인
```

### 문제: 로그가 OpenSearch에 저장되지 않음

**체크리스트**:
1. ✅ Infrastructure 실행 중?
2. ✅ test-spring-app 실행 중?
3. ✅ log-analysis-api 실행 중?
4. ✅ API 호출했음?

**디버깅**:
```bash
# 1. Kafka에 메시지가 있는지 확인
cd infrastructure
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic application-logs \
  --from-beginning \
  --max-messages 1

# 2. log-analysis-api 로그 확인
# "Processing log", "Log saved" 메시지 확인
```

## 📚 추가 정보

### 각 프로젝트 상세 문서

- [infrastructure/README.md](prototype/infrastructure/README.md) - Docker Compose 설명
- [log-collector-library/README.md](prototype/log-collector-library/README.md) - 라이브러리 사용법
- [test-spring-app/README.md](prototype/test-spring-app/README.md) - 테스트 앱 API
- [log-analysis-api/README.md](prototype/log-analysis-api/README.md) - 분석 API 상세
- [INTEGRATION_TEST_GUIDE.md](INTEGRATION_TEST_GUIDE.md) - **통합 테스트 가이드 📖**

### 프로덕션 배포 고려사항

1. **보안**
   - OpenSearch 인증 활성화
   - API 인증 추가 (JWT, API Key 등)
   - HTTPS 적용

2. **확장성**
   - Kafka 파티션 증가
   - OpenSearch 샤드 조정
   - FastAPI workers 증가

3. **모니터링**
   - Prometheus + Grafana
   - OpenSearch 대시보드
   - 로그 수집 지표 추적

4. **비용 최적화**
   - 현재 사용 중인 GPT-4o mini (GPT-4o 대비 94% 저렴)
   - 유사도 임계값 조정 (0.8 → 0.85)
   - 임베딩 캐싱 전략

## 🎉 완료!

모든 시스템이 정상 동작하는지 확인했다면, 이제 실제 프로젝트에 적용할 준비가 되었습니다!

**다음 단계**:
1. 실제 Spring Boot 프로젝트에 `log-collector-library` 통합
2. 프로덕션 환경에 맞게 설정 조정
3. 알림 기능 추가 (Jira, Slack, Mattermost 등)
4. 모니터링 대시보드 구축
"# backend-pass-portfolio" 
