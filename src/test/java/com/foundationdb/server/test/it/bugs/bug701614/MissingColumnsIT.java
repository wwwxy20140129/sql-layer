/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.test.it.bugs.bug701614;

import com.foundationdb.ais.model.AkibanInformationSchema;
import com.foundationdb.server.api.dml.scan.NewRow;
import com.foundationdb.server.error.InvalidOperationException;
import com.foundationdb.server.test.it.ITBase;
import com.foundationdb.util.Strings;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertNotNull;

public final class MissingColumnsIT extends ITBase {
    @Test
    public void testForMissingColumns() throws InvalidOperationException, IOException {
        int tableId = loadBlocksTable();
        writeRows( rows(tableId) );
        expectFullRows(tableId, rows(tableId));
    }

    private int loadBlocksTable() throws InvalidOperationException, IOException {
        final String blocksDDL = Strings.join(Strings.dumpResource(getClass(), "blocks-table.ddl"));
        AkibanInformationSchema tempAIS = createFromDDL("drupal", blocksDDL);
        ddl().createTable(session(), tempAIS.getUserTable("drupal", "blocks"));
        updateAISGeneration();
        AkibanInformationSchema ais = ddl().getAIS(session());
        assertNotNull("drupal.blocks missing from " + ais.getUserTables(), ais.getUserTable("drupal", "blocks"));
        return tableId("drupal", "blocks");
    }

    private NewRow[] rows(int tableId) {
        return new NewRow[] {
                createNewRow(tableId, 1L, "user", "0", "garland", 1L, 0L, "left", 0L, 0L, 0L, "", "", -1L),
                createNewRow(tableId, 2L, "user", "1", "garland", 1L, 0L, "left", 0L, 0L, 0L, "", "", -1L),
                createNewRow(tableId, 3L, "system", "0", "garland", 1L, 10L, "footer", 0L, 0L, 0L, "", "", -1L)
        };
    }
}