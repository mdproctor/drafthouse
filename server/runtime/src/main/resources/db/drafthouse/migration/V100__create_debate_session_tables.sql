CREATE TABLE debate_session (
    channel_id UUID PRIMARY KEY,
    debate_session_id VARCHAR(255) NOT NULL,
    channel_name VARCHAR(255) NOT NULL,
    comparison_path_a VARCHAR(1024),
    comparison_path_b VARCHAR(1024)
);

CREATE TABLE debate_session_document (
    session_channel_id UUID NOT NULL,
    path VARCHAR(1024) NOT NULL,
    label VARCHAR(255) NOT NULL,
    document_order INT NOT NULL,
    CONSTRAINT fk_doc_session FOREIGN KEY (session_channel_id) REFERENCES debate_session(channel_id) ON DELETE CASCADE
);

CREATE TABLE debate_session_participant (
    session_channel_id UUID NOT NULL,
    agent_type VARCHAR(50) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    CONSTRAINT fk_part_session FOREIGN KEY (session_channel_id) REFERENCES debate_session(channel_id) ON DELETE CASCADE
);
