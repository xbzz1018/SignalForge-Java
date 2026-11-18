CREATE TABLE sf_market_snapshot (
  id char(64) PRIMARY KEY,
  project_id uuid NOT NULL REFERENCES sf_project(id),
  task_id uuid NOT NULL REFERENCES sf_task(id),
  security_id varchar(128) NOT NULL,
  market varchar(16) NOT NULL,
  adjustment varchar(16) NOT NULL,
  range_start date NOT NULL,
  range_end date NOT NULL,
  data_version char(64) NOT NULL,
  demo_data boolean NOT NULL,
  series_json jsonb NOT NULL,
  captured_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  reusable_until timestamptz NOT NULL,
  CHECK (range_start <= range_end)
);
CREATE INDEX sf_market_snapshot_lookup ON sf_market_snapshot
  (project_id,security_id,market,adjustment,range_start,range_end,captured_at DESC);
