-- Immutable announcement bodies and reviewed source statements; existing V1-V3 remain unchanged.
ALTER TABLE sf_knowledge_snapshot ADD COLUMN kind varchar(24) NOT NULL DEFAULT 'CATALOG'
  CHECK (kind IN ('CATALOG','REVIEWED_CLAIMS'));

CREATE TABLE sf_document_version (
  id uuid PRIMARY KEY,
  project_id uuid NOT NULL REFERENCES sf_project(id),
  catalog_snapshot_id uuid NOT NULL,
  security_id varchar(16) NOT NULL,
  provider_id varchar(48) NOT NULL,
  title text NOT NULL,
  source_url text NOT NULL,
  published_at timestamptz,
  fetched_at timestamptz NOT NULL,
  pdf_hash char(64) NOT NULL,
  pdf_bytes bytea NOT NULL,
  parser_version varchar(100) NOT NULL,
  parsed_json jsonb NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('READY','PARTIAL','NO_TEXT')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(project_id,id),
  UNIQUE(project_id,security_id,provider_id,pdf_hash,parser_version),
  FOREIGN KEY(project_id,catalog_snapshot_id) REFERENCES sf_knowledge_snapshot(project_id,id),
  CHECK (octet_length(pdf_bytes) BETWEEN 5 AND 6291456)
);

CREATE TABLE sf_evidence_job (
  id uuid PRIMARY KEY,
  project_id uuid NOT NULL REFERENCES sf_project(id),
  created_by uuid NOT NULL REFERENCES sf_user(id),
  kind varchar(16) NOT NULL CHECK (kind IN ('DOCUMENT','EXTRACTION','PUBLICATION')),
  idempotency_key varchar(128) NOT NULL,
  request_hash char(64) NOT NULL,
  request_json jsonb NOT NULL,
  state varchar(16) NOT NULL CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
  result_id uuid,
  attempts integer NOT NULL DEFAULT 0,
  fencing_token bigint NOT NULL DEFAULT 0,
  lease_until timestamptz,
  deadline_at timestamptz NOT NULL,
  error_code varchar(80),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(project_id,idempotency_key),
  UNIQUE(project_id,id)
);
CREATE INDEX sf_evidence_job_dispatch ON sf_evidence_job(state,lease_until,created_at);

CREATE TABLE sf_document_catalog (
  project_id uuid NOT NULL,
  catalog_snapshot_id uuid NOT NULL,
  document_id uuid NOT NULL,
  PRIMARY KEY(project_id,catalog_snapshot_id,document_id),
  FOREIGN KEY(project_id,catalog_snapshot_id) REFERENCES sf_knowledge_snapshot(project_id,id),
  FOREIGN KEY(project_id,document_id) REFERENCES sf_document_version(project_id,id)
);

CREATE TABLE sf_model_budget (
  project_id uuid NOT NULL REFERENCES sf_project(id),
  budget_day date NOT NULL,
  reserved_tokens integer NOT NULL DEFAULT 0 CHECK (reserved_tokens>=0),
  used_tokens integer NOT NULL DEFAULT 0 CHECK (used_tokens>=0),
  PRIMARY KEY(project_id,budget_day)
);

CREATE TABLE sf_extraction_run (
  id uuid PRIMARY KEY,
  project_id uuid NOT NULL REFERENCES sf_project(id),
  job_id uuid NOT NULL UNIQUE REFERENCES sf_evidence_job(id),
  document_id uuid NOT NULL,
  input_hash char(64) NOT NULL,
  schema_version varchar(32) NOT NULL,
  prompt_version varchar(32) NOT NULL,
  model varchar(100) NOT NULL,
  selected_chunks jsonb NOT NULL,
  request_json text NOT NULL,
  state varchar(24) NOT NULL CHECK (state IN ('RESERVED','REQUESTED','RESPONSE_RECEIVED','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
  request_sent_at timestamptz,
  response_json text,
  response_hash char(64),
  reserved_tokens integer NOT NULL,
  actual_tokens integer,
  budget_day date NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY(project_id,document_id) REFERENCES sf_document_version(project_id,id),
  FOREIGN KEY(project_id,job_id) REFERENCES sf_evidence_job(project_id,id)
);

CREATE TABLE sf_claim_candidate (
  id uuid PRIMARY KEY,
  project_id uuid NOT NULL REFERENCES sf_project(id),
  document_id uuid NOT NULL,
  extraction_id uuid NOT NULL REFERENCES sf_extraction_run(id),
  ordinal integer NOT NULL,
  predicate varchar(32) NOT NULL,
  object_text text NOT NULL,
  modality varchar(24) NOT NULL,
  chunk_id varchar(32) NOT NULL,
  quote text NOT NULL,
  quote_start integer NOT NULL,
  quote_end integer NOT NULL,
  raw_json jsonb NOT NULL,
  validation_errors jsonb NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('PENDING','INVALID','APPROVED','REJECTED','PUBLISHED')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(extraction_id,ordinal),
  UNIQUE(project_id,id),
  FOREIGN KEY(project_id,document_id) REFERENCES sf_document_version(project_id,id)
);

CREATE TABLE sf_claim_review (
  candidate_id uuid PRIMARY KEY REFERENCES sf_claim_candidate(id),
  project_id uuid NOT NULL REFERENCES sf_project(id),
  reviewer_id uuid NOT NULL REFERENCES sf_user(id),
  decision varchar(16) NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
  reason varchar(500) NOT NULL,
  reviewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY(project_id,candidate_id) REFERENCES sf_claim_candidate(project_id,id)
);

CREATE TABLE sf_claim_publication (
  snapshot_id uuid PRIMARY KEY REFERENCES sf_knowledge_snapshot(id),
  project_id uuid NOT NULL REFERENCES sf_project(id),
  catalog_snapshot_id uuid NOT NULL,
  candidate_ids jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY(project_id,snapshot_id) REFERENCES sf_knowledge_snapshot(project_id,id),
  FOREIGN KEY(project_id,catalog_snapshot_id) REFERENCES sf_knowledge_snapshot(project_id,id)
);

CREATE TABLE sf_claim_outbox (
  job_id uuid PRIMARY KEY REFERENCES sf_evidence_job(id),
  snapshot_id uuid NOT NULL REFERENCES sf_knowledge_snapshot(id),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  delivered_at timestamptz
);
