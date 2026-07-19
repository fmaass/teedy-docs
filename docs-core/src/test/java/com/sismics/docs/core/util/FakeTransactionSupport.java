package com.sismics.docs.core.util;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import java.lang.reflect.Proxy;

/**
 * Test-only controllable JPA transaction + entity manager. These are DEPENDENCIES of the transaction
 * owners under test (never a mock of the unit itself): they let a test drive a commit / rollback /
 * begin / close failure — which the embedded H2 database cannot inject on demand — through the REAL
 * owner code paths (TransactionUtil.commit, FavoriteDao.beginFreshTransaction, TransactionBoundary,
 * RequestContextFilter.commitAndFinalize).
 */
public final class FakeTransactionSupport {

    private FakeTransactionSupport() {
    }

    /** A controllable JPA transaction whose commit / rollback / begin can be made to throw. */
    public static final class FakeTx implements EntityTransaction {
        public boolean active;
        public boolean commitThrows;
        public boolean rollbackThrows;
        public boolean beginThrows;
        public int commitCount;
        public int rollbackCount;
        public int beginCount;

        @Override
        public void begin() {
            beginCount++;
            if (beginThrows) {
                throw new IllegalStateException("injected begin failure");
            }
            active = true;
        }

        @Override
        public void commit() {
            commitCount++;
            if (commitThrows) {
                throw new RuntimeException("injected commit failure");
            }
            active = false;
        }

        @Override
        public void rollback() {
            rollbackCount++;
            if (rollbackThrows) {
                throw new RuntimeException("injected rollback failure");
            }
            active = false;
        }

        @Override
        public void setRollbackOnly() {
            // no-op
        }

        @Override
        public boolean getRollbackOnly() {
            return false;
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }

    /**
     * A fake EntityManager exposing only the methods the transaction owners touch: {@code isOpen},
     * {@code getTransaction}, {@code close}, and the no-op {@code flush}/{@code clear} the checkpoint /
     * favorite paths call. {@code open[0]}/{@code closed[0]} let a test observe close; {@code closeFailure}
     * (if non-null) is thrown by {@code close()} to exercise the post-outcome observer policy.
     */
    public static EntityManager fakeEntityManager(EntityTransaction tx, boolean[] closed, boolean[] open,
                                                  Throwable closeFailure) {
        return fakeEntityManager(tx, closed, open, closeFailure, null);
    }

    /**
     * As above, plus {@code getTransactionFailure}: when non-null, {@code getTransaction()} throws it —
     * used to leave the fresh manager OPEN at the outer finally so the backstop close is the one that
     * fails, exercising the guaranteed-pop invariant.
     */
    public static EntityManager fakeEntityManager(EntityTransaction tx, boolean[] closed, boolean[] open,
                                                  Throwable closeFailure, Throwable getTransactionFailure) {
        return (EntityManager) Proxy.newProxyInstance(
                FakeTransactionSupport.class.getClassLoader(),
                new Class<?>[]{EntityManager.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isOpen":
                            return open[0];
                        case "getTransaction":
                            if (getTransactionFailure != null) {
                                throw getTransactionFailure;
                            }
                            return tx;
                        case "close":
                            closed[0] = true;
                            open[0] = false;
                            if (closeFailure != null) {
                                throw closeFailure;
                            }
                            return null;
                        case "flush":
                        case "clear":
                            return null;
                        case "toString":
                            return "FakeEntityManager";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                return false;
                            }
                            if (rt.isPrimitive() && rt != void.class) {
                                return 0;
                            }
                            return null;
                    }
                });
    }

    public static EntityManager fakeEntityManager(EntityTransaction tx, boolean[] closed, boolean[] open) {
        return fakeEntityManager(tx, closed, open, null);
    }
}
