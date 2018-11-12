/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.dbms.database;

import java.io.File;
import java.io.IOException;
import java.nio.file.OpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;

import static java.util.Objects.requireNonNull;

public class DatabasePageCache implements PageCache
{
    private final PageCache globalPageCache;
    private final CopyOnWriteArrayList<PagedFile> databasePagedFiles = new CopyOnWriteArrayList<>();
    private boolean closed;

    public DatabasePageCache( PageCache globalPageCache )
    {
        requireNonNull( globalPageCache );
        this.globalPageCache = globalPageCache;
    }

    @Override
    public synchronized PagedFile map( File file, int pageSize, OpenOption... openOptions ) throws IOException
    {
        PagedFile pagedFile = globalPageCache.map( file, pageSize, openOptions );
        DatabasePageFile databasePageFile = new DatabasePageFile( pagedFile, databasePagedFiles );
        databasePagedFiles.add( databasePageFile );
        return databasePageFile;
    }

    @Override
    public Optional<PagedFile> getExistingMapping( File file ) throws IOException
    {
        File canonicalFile = file.getCanonicalFile();

        return databasePagedFiles.stream().filter( pagedFile -> pagedFile.file().equals( canonicalFile ) ).findFirst();
    }

    @Override
    public List<PagedFile> listExistingMappings()
    {
        return new ArrayList<>( databasePagedFiles );
    }

    @Override
    public void flushAndForce() throws IOException
    {
        for ( PagedFile pagedFile : databasePagedFiles )
        {
            pagedFile.flushAndForce();
        }
    }

    @Override
    public void flushAndForce( IOLimiter limiter ) throws IOException
    {
        for ( PagedFile pagedFile : databasePagedFiles )
        {
            pagedFile.flushAndForce( limiter );
        }
    }

    @Override
    public synchronized void close() throws IllegalStateException
    {
        if ( closed )
        {
            throw new IllegalStateException( "Database page cache was already closed" );
        }
        for ( PagedFile pagedFile : databasePagedFiles )
        {
            pagedFile.close();
        }
        databasePagedFiles.clear();
        closed = true;
    }

    @Override
    public int pageSize()
    {
        return globalPageCache.pageSize();
    }

    @Override
    public long maxCachedPages()
    {
        return globalPageCache.maxCachedPages();
    }

    @Override
    public void reportEvents()
    {
        globalPageCache.reportEvents();
    }

    private static class DatabasePageFile implements PagedFile
    {
        private final PagedFile delegate;
        private final List<PagedFile> databaseFiles;

        DatabasePageFile( PagedFile delegate, List<PagedFile> databaseFiles )
        {
            this.delegate = delegate;
            this.databaseFiles = databaseFiles;
        }

        @Override
        public PageCursor io( long pageId, int pf_flags ) throws IOException
        {
            return delegate.io( pageId, pf_flags );
        }

        @Override
        public int pageSize()
        {
            return delegate.pageSize();
        }

        @Override
        public long fileSize() throws IOException
        {
            return delegate.fileSize();
        }

        @Override
        public File file()
        {
            return delegate.file();
        }

        @Override
        public void flushAndForce() throws IOException
        {
            delegate.flushAndForce();
        }

        @Override
        public void flushAndForce( IOLimiter limiter ) throws IOException
        {
            delegate.flushAndForce( limiter );
        }

        @Override
        public long getLastPageId() throws IOException
        {
            return delegate.getLastPageId();
        }

        @Override
        public void close()
        {
            delegate.close();
            databaseFiles.remove( this );
        }
    }
}
