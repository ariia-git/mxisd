/*
 * mxisd - Matrix Identity Server Daemon
 * Copyright (C) 2017 Kamax Sarl
 *
 * https://www.kamax.io/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.kamax.mxisd.storage.ormlite;

import com.j256.ormlite.dao.CloseableWrappedIterable;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;
import io.kamax.matrix.ThreePid;
import io.kamax.mxisd.config.MxisdConfig;
import io.kamax.mxisd.exception.ConfigurationException;
import io.kamax.mxisd.exception.InternalServerError;
import io.kamax.mxisd.invitation.IThreePidInviteReply;
import io.kamax.mxisd.storage.IStorage;
import io.kamax.mxisd.storage.dao.IThreePidSessionDao;
import io.kamax.mxisd.storage.ormlite.dao.ASTransactionDao;
import io.kamax.mxisd.storage.ormlite.dao.ThreePidInviteIO;
import io.kamax.mxisd.storage.ormlite.dao.ThreePidSessionDao;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class OrmLiteSqlStorage implements IStorage {

    private transient final Logger log = LoggerFactory.getLogger(OrmLiteSqlStorage.class);

    @FunctionalInterface
    private interface Getter<T> {

        T get() throws SQLException, IOException;

    }

    @FunctionalInterface
    private interface Doer {

        void run() throws SQLException, IOException;

    }

    private Dao<ThreePidInviteIO, String> invDao;
    private Dao<ThreePidSessionDao, String> sessionDao;
    private Dao<ASTransactionDao, String> asTxnDao;

    public OrmLiteSqlStorage(MxisdConfig cfg) {
        this(cfg.getStorage().getBackend(), cfg.getStorage().getProvider().getSqlite().getDatabase());
    }

    public OrmLiteSqlStorage(String backend, String path) {
        if (StringUtils.isBlank(backend)) {
            throw new ConfigurationException("storage.backend");
        }

        if (StringUtils.isBlank(path)) {
            throw new ConfigurationException("Storage destination cannot be empty");
        }

        withCatcher(() -> {
            ConnectionSource connPool = new JdbcConnectionSource("jdbc:" + backend + ":" + path);
            invDao = createDaoAndTable(connPool, ThreePidInviteIO.class);
            sessionDao = createDaoAndTable(connPool, ThreePidSessionDao.class);
            asTxnDao = createDaoAndTable(connPool, ASTransactionDao.class);
        });
    }

    private <V, K> Dao<V, K> createDaoAndTable(ConnectionSource connPool, Class<V> c) throws SQLException {
        Dao<V, K> dao = DaoManager.createDao(connPool, c);
        TableUtils.createTableIfNotExists(connPool, c);
        return dao;
    }

    private <T> T withCatcher(Getter<T> g) {
        try {
            return g.get();
        } catch (SQLException | IOException e) {
            throw new RuntimeException(e); // FIXME do better
        }
    }

    private void withCatcher(Doer d) {
        try {
            d.run();
        } catch (SQLException | IOException e) {
            throw new RuntimeException(e); // FIXME do better
        }
    }

    private <T> List<T> forIterable(CloseableWrappedIterable<? extends T> t) {
        return withCatcher(() -> {
            try {
                List<T> ioList = new ArrayList<>();
                t.forEach(ioList::add);
                return ioList;
            } finally {
                t.close();
            }
        });
    }

    @Override
    public Collection<ThreePidInviteIO> getInvites() {
        return forIterable(invDao.getWrappedIterable());
    }

    @Override
    public void insertInvite(IThreePidInviteReply data) {
        withCatcher(() -> {
            int updated = invDao.create(new ThreePidInviteIO(data));
            if (updated != 1) {
                throw new RuntimeException("Unexpected row count after DB action: " + updated);
            }
        });
    }

    @Override
    public void deleteInvite(String id) {
        withCatcher(() -> {
            int updated = invDao.deleteById(id);
            if (updated != 1) {
                throw new RuntimeException("Unexpected row count after DB action: " + updated);
            }
        });
    }

    @Override
    public Optional<IThreePidSessionDao> getThreePidSession(String sid) {
        return withCatcher(() -> Optional.ofNullable(sessionDao.queryForId(sid)));
    }

    @Override
    public Optional<IThreePidSessionDao> findThreePidSession(ThreePid tpid, String secret) {
        return withCatcher(() -> {
            List<ThreePidSessionDao> daoList = sessionDao.queryForMatchingArgs(new ThreePidSessionDao(tpid, secret));
            if (daoList.size() > 1) {
                throw new InternalServerError("Lookup for 3PID Session " +
                        tpid + " returned more than one result");
            }

            if (daoList.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(daoList.get(0));
        });
    }

    @Override
    public void insertThreePidSession(IThreePidSessionDao session) {
        withCatcher(() -> {
            int updated = sessionDao.create(new ThreePidSessionDao(session));
            if (updated != 1) {
                throw new RuntimeException("Unexpected row count after DB action: " + updated);
            }
        });
    }

    @Override
    public void updateThreePidSession(IThreePidSessionDao session) {
        withCatcher(() -> {
            int updated = sessionDao.update(new ThreePidSessionDao(session));
            if (updated != 1) {
                throw new RuntimeException("Unexpected row count after DB action: " + updated);
            }
        });
    }

    @Override
    public void insertTransactionResult(String localpart, String txnId, Instant completion, String result) {
        withCatcher(() -> {
            int created = asTxnDao.create(new ASTransactionDao(localpart, txnId, completion, result));
            if (created != 1) {
                throw new RuntimeException("Unexpected row count after DB action: " + created);
            }
        });
    }

    @Override
    public Optional<ASTransactionDao> getTransactionResult(String localpart, String txnId) {
        return withCatcher(() -> {
            ASTransactionDao dao = new ASTransactionDao();
            dao.setLocalpart(localpart);
            dao.setTransactionId(txnId);
            List<ASTransactionDao> daoList = asTxnDao.queryForMatchingArgs(dao);

            if (daoList.size() > 1) {
                throw new InternalServerError("Lookup for Transaction " +
                        txnId + " for localpart " + localpart + " returned more than one result");
            }

            if (daoList.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(daoList.get(0));
        });
    }

}
