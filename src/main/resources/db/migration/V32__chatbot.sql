-- 챗봇 질문 한도용 학생별, 날짜별 질문 수. 질문 내용은 저장하지 않음
CREATE TABLE chatbot_usage (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    count INT NOT NULL,
    PRIMARY KEY (user_id, day)
);

-- 답변 평가와 근거 출처 주소만 저장(누가, 무엇을 물었는지는 남기지 않음)
CREATE TABLE chatbot_feedback (
    id BIGSERIAL PRIMARY KEY,
    rating VARCHAR(16) NOT NULL CHECK (rating IN ('helpful', 'not_helpful')),
    source_urls TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
