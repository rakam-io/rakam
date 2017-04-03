CREATE TABLE IF NOT EXISTS ui_user_defaults (
  user_id INT REFERENCES web_user(id) ON DELETE CASCADE,
  project_id INTEGER REFERENCES web_user_project(id) ON DELETE CASCADE,
  name varchar(100),
  value TEXT,
  created_at timestamp DEFAULT now() NOT NULL,
  PRIMARY KEY (user_id, project_id, name)
)