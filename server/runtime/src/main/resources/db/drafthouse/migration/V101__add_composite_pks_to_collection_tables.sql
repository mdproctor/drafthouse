ALTER TABLE debate_session_document
    ADD PRIMARY KEY (session_channel_id, document_order);

ALTER TABLE debate_session_participant
    ADD PRIMARY KEY (session_channel_id, agent_type);
