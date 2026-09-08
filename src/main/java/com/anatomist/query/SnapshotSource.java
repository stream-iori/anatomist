package com.anatomist.query;

import com.anatomist.version.SnapshotFiles;
import java.nio.file.Path;
import java.sql.*;

/** Source-location indirection shared by declaration and context readers. */
public final class SnapshotSource {
    private SnapshotSource() {}
    public static Path path(Connection connection,String sourceFile) throws SQLException {
        try(PreparedStatement s=connection.prepareStatement("SELECT m.value,f.hash FROM project_meta m JOIN file_cache f "
                + "ON f.source_file=? WHERE m.key='snapshot_blob_root'")) {
            s.setString(1,sourceFile);
            try(ResultSet r=s.executeQuery()) {
                if(r.next()) return new com.anatomist.store.SourceBlobStore(Path.of(r.getString(1))).path(r.getString(2));
            }
        }
        try(PreparedStatement s=connection.prepareStatement("SELECT value FROM project_meta WHERE key='snapshot_sources'");
            ResultSet r=s.executeQuery()) {
            return r.next()?SnapshotFiles.resolve(Path.of(r.getString(1)),sourceFile):null;
        }
    }
}
