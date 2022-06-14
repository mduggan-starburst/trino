/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.trino.operator.join;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.trino.execution.Lifespan;
import io.trino.operator.ReferenceCount;
import io.trino.spi.type.Type;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Objects.requireNonNull;

public class JoinBridgeManager<T extends JoinBridge>
{
    @VisibleForTesting
    public static JoinBridgeManager<PartitionedLookupSourceFactory> lookupAllAtOnce(PartitionedLookupSourceFactory factory)
    {
        return new JoinBridgeManager<>(
                false,
                ignored -> factory,
                factory.getOutputTypes());
    }

    private final List<Type> buildOutputTypes;
    private final boolean buildOuter;
    private final Function<Lifespan, T> joinBridgeProvider;

    private final FreezeOnReadCounter probeFactoryCount = new FreezeOnReadCounter();

    private final AtomicBoolean initialized = new AtomicBoolean();
    private InternalJoinBridgeDataManager<T> internalJoinBridgeDataManager;

    public JoinBridgeManager(
            boolean buildOuter,
            Function<Lifespan, T> lookupSourceFactoryProvider,
            List<Type> buildOutputTypes)
    {
        this.buildOuter = buildOuter;
        this.joinBridgeProvider = requireNonNull(lookupSourceFactoryProvider, "lookupSourceFactoryProvider is null");
        this.buildOutputTypes = requireNonNull(buildOutputTypes, "buildOutputTypes is null");
    }

    private void initializeIfNecessary()
    {
        if (!initialized.get()) {
            synchronized (this) {
                if (initialized.get()) {
                    return;
                }
                int finalProbeFactoryCount = probeFactoryCount.get();
                internalJoinBridgeDataManager = internalJoinBridgeDataManager(joinBridgeProvider, finalProbeFactoryCount, buildOuter ? 1 : 0);
                initialized.set(true);
            }
        }
    }

    public List<Type> getBuildOutputTypes()
    {
        return buildOutputTypes;
    }

    public void incrementProbeFactoryCount()
    {
        probeFactoryCount.increment();
    }

    public T getJoinBridge(Lifespan lifespan)
    {
        initializeIfNecessary();
        return internalJoinBridgeDataManager.getJoinBridge(lifespan);
    }

    /**
     * Invoked when a probe operator factory indicates that it will not
     * create any more operators, for any lifespan.
     * <p>
     * It is expected that this method will only be invoked after
     * {@link #probeOperatorFactoryClosed(Lifespan)} has been invoked
     * for every known lifespan.
     */
    public void probeOperatorFactoryClosedForAllLifespans()
    {
        initializeIfNecessary();
        internalJoinBridgeDataManager.probeOperatorFactoryClosedForAllLifespans();
    }

    public void probeOperatorFactoryClosed(Lifespan lifespan)
    {
        initializeIfNecessary();
        internalJoinBridgeDataManager.probeOperatorFactoryClosed(lifespan);
    }

    public void probeOperatorCreated(Lifespan lifespan)
    {
        initializeIfNecessary();
        internalJoinBridgeDataManager.probeOperatorCreated(lifespan);
    }

    public void probeOperatorClosed(Lifespan lifespan)
    {
        initializeIfNecessary();
        internalJoinBridgeDataManager.probeOperatorClosed(lifespan);
    }

    public void outerOperatorFactoryClosed(Lifespan lifespan)
    {
        initializeIfNecessary();
        internalJoinBridgeDataManager.outerOperatorFactoryClosed(lifespan);
    }

    public void outerOperatorCreated(Lifespan lifespan)
    {
        initializeIfNecessary();
        internalJoinBridgeDataManager.outerOperatorCreated(lifespan);
    }

    public void outerOperatorClosed(Lifespan lifespan)
    {
        initializeIfNecessary();
        internalJoinBridgeDataManager.outerOperatorClosed(lifespan);
    }

    public ListenableFuture<OuterPositionIterator> getOuterPositionsFuture(Lifespan lifespan)
    {
        initializeIfNecessary();
        return internalJoinBridgeDataManager.getOuterPositionsFuture(lifespan);
    }

    private static <T extends JoinBridge> InternalJoinBridgeDataManager<T> internalJoinBridgeDataManager(
            Function<Lifespan, T> joinBridgeProvider,
            int probeFactoryCount,
            int outerFactoryCount)
    {
        checkArgument(outerFactoryCount == 0 || outerFactoryCount == 1, "outerFactoryCount should only be 0 or 1 because it is expected that outer factory never gets duplicated.");
        return new TaskWideInternalJoinBridgeDataManager<>(joinBridgeProvider, probeFactoryCount, outerFactoryCount);
    }

    private interface InternalJoinBridgeDataManager<T extends JoinBridge>
    {
        T getJoinBridge(Lifespan lifespan);

        ListenableFuture<OuterPositionIterator> getOuterPositionsFuture(Lifespan lifespan);

        void probeOperatorFactoryClosedForAllLifespans();

        void probeOperatorFactoryClosed(Lifespan lifespan);

        void probeOperatorCreated(Lifespan lifespan);

        void probeOperatorClosed(Lifespan lifespan);

        void outerOperatorFactoryClosed(Lifespan lifespan);

        void outerOperatorCreated(Lifespan lifespan);

        void outerOperatorClosed(Lifespan lifespan);
    }

    // 1 probe, 1 lookup source
    private static class TaskWideInternalJoinBridgeDataManager<T extends JoinBridge>
            implements InternalJoinBridgeDataManager<T>
    {
        private final T joinBridge;
        private final JoinLifecycle joinLifecycle;

        public TaskWideInternalJoinBridgeDataManager(Function<Lifespan, T> lookupSourceFactoryProvider, int probeFactoryCount, int outerFactoryCount)
        {
            joinBridge = lookupSourceFactoryProvider.apply(Lifespan.taskWide());
            joinLifecycle = new JoinLifecycle(joinBridge, probeFactoryCount, outerFactoryCount);
        }

        @Override
        public T getJoinBridge(Lifespan lifespan)
        {
            checkArgument(Lifespan.taskWide().equals(lifespan));
            return joinBridge;
        }

        @Override
        public ListenableFuture<OuterPositionIterator> getOuterPositionsFuture(Lifespan lifespan)
        {
            checkArgument(Lifespan.taskWide().equals(lifespan));
            return transform(joinLifecycle.whenBuildAndProbeFinishes(), ignored -> joinBridge.getOuterPositionIterator(), directExecutor());
        }

        @Override
        public void probeOperatorFactoryClosedForAllLifespans()
        {
            // do nothing
        }

        @Override
        public void probeOperatorFactoryClosed(Lifespan lifespan)
        {
            checkArgument(Lifespan.taskWide().equals(lifespan));
            joinLifecycle.releaseForProbe();
        }

        @Override
        public void probeOperatorCreated(Lifespan lifespan)
        {
            checkArgument(Lifespan.taskWide().equals(lifespan));
            joinLifecycle.retainForProbe();
        }

        @Override
        public void probeOperatorClosed(Lifespan lifespan)
        {
            checkArgument(Lifespan.taskWide().equals(lifespan));
            joinLifecycle.releaseForProbe();
        }

        @Override
        public void outerOperatorFactoryClosed(Lifespan lifespan)
        {
            checkArgument(Lifespan.taskWide().equals(lifespan));
            joinLifecycle.releaseForOuter();
        }

        @Override
        public void outerOperatorCreated(Lifespan lifespan)
        {
            checkArgument(Lifespan.taskWide().equals(lifespan));
            joinLifecycle.retainForOuter();
        }

        @Override
        public void outerOperatorClosed(Lifespan lifespan)
        {
            checkArgument(Lifespan.taskWide().equals(lifespan));
            joinLifecycle.releaseForOuter();
        }
    }

    private static class JoinLifecycle
    {
        private final ReferenceCount probeReferenceCount;
        private final ReferenceCount outerReferenceCount;

        private final ListenableFuture<Void> whenBuildAndProbeFinishes;
        private final ListenableFuture<Void> whenAllFinishes;

        public JoinLifecycle(JoinBridge joinBridge, int probeFactoryCount, int outerFactoryCount)
        {
            // When all probe and lookup-outer operators finish, destroy the join bridge (freeing the memory)
            // * Each LookupOuterOperatorFactory count as 1
            //   * There is at most 1 LookupOuterOperatorFactory
            // * Each LookupOuterOperator count as 1
            checkArgument(outerFactoryCount == 0 || outerFactoryCount == 1);
            outerReferenceCount = new ReferenceCount(outerFactoryCount);

            // * Each probe operator factory count as 1
            // * Each probe operator count as 1
            probeReferenceCount = new ReferenceCount(probeFactoryCount);

            whenBuildAndProbeFinishes = Futures.whenAllSucceed(joinBridge.whenBuildFinishes(), probeReferenceCount.getFreeFuture()).call(() -> null, directExecutor());
            whenAllFinishes = Futures.whenAllSucceed(whenBuildAndProbeFinishes, outerReferenceCount.getFreeFuture()).call(() -> null, directExecutor());
            whenAllFinishes.addListener(joinBridge::destroy, directExecutor());
        }

        public ListenableFuture<Void> whenBuildAndProbeFinishes()
        {
            return whenBuildAndProbeFinishes;
        }

        private void retainForProbe()
        {
            probeReferenceCount.retain();
        }

        private void releaseForProbe()
        {
            probeReferenceCount.release();
        }

        private void retainForOuter()
        {
            outerReferenceCount.retain();
        }

        private void releaseForOuter()
        {
            outerReferenceCount.release();
        }
    }

    private static class FreezeOnReadCounter
    {
        private int count;
        private boolean frozen;

        public synchronized void increment()
        {
            checkState(!frozen, "Counter has been read");
            count++;
        }

        public synchronized int get()
        {
            frozen = true;
            return count;
        }
    }
}
