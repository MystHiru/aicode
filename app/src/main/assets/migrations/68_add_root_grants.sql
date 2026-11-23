-- root 通道的「始终允许」授权记录：按规范化路径 + 操作类型记忆，允许写不等于允许删
CREATE TABLE IF NOT EXISTS root_grants (
    id TEXT NOT NULL PRIMARY KEY,
    realPath TEXT NOT NULL,
    operation TEXT NOT NULL,
    scope TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    revocable INTEGER NOT NULL DEFAULT 1
);

CREATE UNIQUE INDEX IF NOT EXISTS index_root_grants_realPath_operation
    ON root_grants (realPath, operation);
