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

package com.akiban.server.store.statistics;

import com.akiban.ais.model.Index;
import com.akiban.server.service.tree.KeyCreator;
import com.akiban.server.store.statistics.histograms.Sampler;
import com.persistit.Key;
import com.persistit.Value;

class PersistitMultiColumnIndexStatisticsVisitor extends IndexStatisticsGenerator<Key,Value>
{
    @Override
    public void visit(Key key, Value value)
    {
        loadKey(key);
    }

    @Override
    public Sampler<Key> createKeySampler(int bucketCount, long distinctCount) {
        return new Sampler<>(
                new PersistitKeySplitter(columnCount(), getKeysFlywheel()),
                bucketCount,
                distinctCount,
                getKeysFlywheel()
        );
    }

    @Override
    protected byte[] copyOfKeyBytes(Key key) {
        byte[] copy = new byte[key.getEncodedSize()];
        System.arraycopy(key.getEncodedBytes(), 0, copy, 0, copy.length);
        return copy;
    }

    public PersistitMultiColumnIndexStatisticsVisitor(Index index, KeyCreator keyCreator)
    {
        super(new PersistitKeyFlywheel(keyCreator), index, index.getKeyColumns().size(), -1);
    }
}
