-- V005__terminal_settings_login_bg.sql — an id and a media type for the login background (TASK B21).
--
-- ADDITIVE, per backend/ARCHITECTURE.md §4.2 ("subsequent migrations numbered sequentially;
-- additive-only during MVP"). Nothing already in terminal_settings is redesigned.
--
-- Why it is needed. docs/backend-architecture.md §2 gives the table one column for the picture --
-- `login_bg BYTEA -- admin-uploaded image (or object-store id)` -- but docs/api-contract.md
-- (Settings) is the authority on shapes, and it says the settings object carries a
-- `loginBgImageId?` and that the upload answers with an id. An id the row does not store cannot be
-- handed out, so the bytes get two companions:
--
--   login_bg_image_id      the opaque id GET/PUT /terminal-settings reports and
--                          GET /terminal-settings/login-bg/{imageId} is addressed by. Random per
--                          upload, so a replaced background never reuses a cached URL, and unique
--                          across terminals so the serve path is a single lookup.
--   login_bg_content_type  what to answer the serve request with. Sniffed from the bytes at
--                          upload time, never trusted from the client's part header.
--
-- All three columns move together: either the terminal has a background (all NOT NULL) or it has
-- none (all NULL). "Remove" in design.md §6 is PUT with loginBgImageId: null, which clears the
-- bytes with the id rather than leaving an unreachable blob behind -- the CHECK below is what
-- makes that pairing a schema rule instead of a convention.

ALTER TABLE terminal_settings
  ADD COLUMN login_bg_image_id TEXT UNIQUE,
  ADD COLUMN login_bg_content_type TEXT,
  ADD CONSTRAINT terminal_settings_login_bg_complete CHECK (
    (login_bg IS NULL AND login_bg_image_id IS NULL AND login_bg_content_type IS NULL)
    OR (login_bg IS NOT NULL AND login_bg_image_id IS NOT NULL AND login_bg_content_type IS NOT NULL)
  );

COMMENT ON COLUMN terminal_settings.login_bg_image_id IS
  'opaque id of the stored login background; the {imageId} in GET /terminal-settings/login-bg/{imageId}';

COMMENT ON COLUMN terminal_settings.login_bg_content_type IS
  'image media type sniffed from the uploaded bytes, replayed on the serve response';
