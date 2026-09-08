package com.anatomist.query;

import com.anatomist.version.SnapshotFiles;
import java.nio.file.Path;
import java.sql.*;

/** Source-location indirection shared by declaration and context readers. */
public final class SnapshotSource {
    private SnapshotSource() {}
    public static Path path(Connection connection,String sourceFile) throws SQLException {
        try(PreparedStatement s=connection.prepareStatement("SELECT value FROM project_meta WHERE key='snapshot_sources'");
            ResultSet r=s.executeQuery()) {
            return r.next()?SnapshotFiles.resolve(Path.of(r.getString(1)),sourceFile):null;
        }
    }
}
