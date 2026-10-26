package org.openlcb.implementations;

import org.junit.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Created by bracz on 4/3/17.
 */
public class VersionedValueTest  {
    VersionedValue<Integer> v = new VersionedValue<>(42);

    @Test
    public void testDefaultAndSet() throws Exception {
        Assert.assertEquals("default value", Integer.valueOf(42), v.getLatestData());
        Assert.assertEquals(v.DEFAULT_VERSION, v.getVersion());
        Assert.assertTrue(v.isVersionAtDefault());
        Assert.assertEquals(42, (int)v.getLatestData());

        v.set(13);
        Assert.assertEquals("set value", Integer.valueOf(13), v.getLatestData());
        assert(v.DEFAULT_VERSION < v.getVersion());
        Assert.assertFalse(v.isVersionAtDefault());
        Assert.assertEquals(13, (int)v.getLatestData());

        v.setVersionToDefault();
        assert(v.DEFAULT_VERSION < v.getVersion());
        Assert.assertTrue(v.isVersionAtDefault());
        Assert.assertEquals(13, (int)v.getLatestData());
    }

    @Test
    public void testSetWithForceNotify() throws Exception {
        v.set(33); // gets rid of the default value condition

        MockVersionedValueListener<Integer> l1 = new MockVersionedValueListener<>(v);
        MockVersionedValueListener<Integer> l2 = new MockVersionedValueListener<>(v);

        l1.setFromOwnerWithForceNotify(33);
        verify(l2.stub).update(33);
        verifyNoMoreInteractions(l2.stub);
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub, l2.stub);

        l2.setFromOwnerWithForceNotify(33);
        verify(l1.stub).update(33);
        verifyNoMoreInteractions(l2.stub);
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub, l2.stub);

        v.setWithForceNotify(v.getNewVersion(), 33);
        verify(l1.stub).update(33);
        verify(l2.stub).update(33);
        verifyNoMoreInteractions(l2.stub);
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub, l2.stub);
    }

    @Test
    public void testSet() throws Exception {
        MockVersionedValueListener<Integer> l1 = new MockVersionedValueListener<>(v);
        MockVersionedValueListener<Integer> l2 = new MockVersionedValueListener<>(v);

        l1.setFromOwner(42);
        verify(l2.stub).update(42);
        verifyNoMoreInteractions(l2.stub);
        // no callback to the owner
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub, l2.stub);

        // skips duplicate notification
        l1.setFromOwner(42);
        verifyNoMoreInteractions(l2.stub);
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub, l2.stub);

        l1.setFromOwner(13);
        verify(l2.stub).update(13);
        verifyNoMoreInteractions(l2.stub);
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub, l2.stub);

        l2.setFromOwner(81);
        verify(l1.stub).update(81);
        verifyNoMoreInteractions(l2.stub);
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub, l2.stub);

        // Unowned set will trigger callbacks both ways.
        v.set(33);
        verify(l1.stub).update(33);
        verify(l2.stub).update(33);
        verifyNoMoreInteractions(l2.stub);
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub, l2.stub);

        // skips duplicate notification
        l1.setFromOwner(33);
        verifyNoMoreInteractions(l2.stub);
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub, l2.stub);

        // After reset to default we will get an update notification.
        v.setVersionToDefault();
        l1.setFromOwner(33);
        verify(l2.stub).update(33);
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub, l2.stub);

        // After reset to default we will get both update notifications.
        v.setVersionToDefault();
        v.set(33);
        verify(l1.stub).update(33);
        verify(l2.stub).update(33);
        reset(l1.stub, l2.stub);

        // After reset to default we will get an update notification.
        v.setVersionToDefault();
        l1.setFromOwner(42);
        verify(l2.stub).update(42);
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub, l2.stub);
    }

    @Test
    public void testOutOfOrder() throws Exception {
        v.set(33);

        MockVersionedValueListener<Integer> l1 = new MockVersionedValueListener<>(v);

        int ver1 = v.getNewVersion();
        int ver2 = v.getNewVersion();
        v.set(ver2, 21);

        verify(l1.stub).update(21);
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub);

        v.set(ver1, 13);
        // out of order update will be swallowed.
        verifyNoMoreInteractions(l1.stub);
        reset(l1.stub);
    }

    /**
     * A value that runs a hook straight after handing out a version, so that a test can make
     * another thread act between the moment a setter takes its version and the moment it
     * stores the value.
     */
    private static class PausingValue extends VersionedValue<Boolean> {
        Runnable afterNextNewVersion = null;

        PausingValue(boolean t) {
            super(t);
        }

        @Override
        public int getNewVersion() {
            int newVersion = super.getNewVersion();
            Runnable hook = afterNextNewVersion;
            afterNextNewVersion = null;
            if (hook != null) {
                hook.run();
            }
            return newVersion;
        }
    }

    /**
     * Runs a set on another thread and returns once that set has either finished or is waiting
     * for the lock of the value. Decides by the thread's state and the lock it waits for, not by
     * time.
     */
    private static Thread runSetOnOtherThread(VersionedValue<Boolean> value, Runnable set) {
        Thread other = new Thread(set, "other setter");
        other.start();
        while (other.isAlive()) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(other.getId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockInfo() != null
                    && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(value)) {
                break;
            }
            Thread.yield();
        }
        return other;
    }

    /**
     * The owner takes a version for its change, and before storing it another thread sets the
     * value from the network. The owner's change must still be stored and notified, and not be
     * refused as out of date. The two notifications are sent after the lock is released, so
     * their order is not fixed.
     * @param ownerSets  the owner's set of false; runs on the test thread
     */
    private void helperOwnerChangeNotLost(PausingValue value,
                                          java.util.function.Consumer<Boolean> ownerSets,
                                          MockVersionedValueListener<Boolean> network)
            throws InterruptedException {
        List<Boolean> applied = Collections.synchronizedList(new ArrayList<>());
        value.addPropertyChangeListener(e -> applied.add((Boolean) e.getNewValue()));
        Thread[] other = new Thread[1];
        value.afterNextNewVersion = () ->
                other[0] = runSetOnOtherThread(value, () -> network.setFromOwner(true));
        ownerSets.accept(false);
        other[0].join();

        List<Boolean> sorted = new ArrayList<>(applied);
        Collections.sort(sorted);
        Assert.assertEquals("both sets stored and notified", java.util.Arrays.asList(false, true),
                sorted);
        // The network set took the later version.
        Assert.assertTrue(value.getLatestData());
    }

    @Test
    public void testOwnerChangeNotLostToConcurrentSet() throws Exception {
        PausingValue value = new PausingValue(true);
        value.set(true); // leaves the default version
        MockVersionedValueListener<Boolean> owner = new MockVersionedValueListener<>(value);
        MockVersionedValueListener<Boolean> network = new MockVersionedValueListener<>(value);
        helperOwnerChangeNotLost(value, owner::setFromOwner, network);
        // The owner is told of the newer value from the network.
        verify(owner.stub).update(true);
        verifyNoMoreInteractions(owner.stub);
    }

    @Test
    public void testOwnerForcedChangeNotLostToConcurrentSet() throws Exception {
        PausingValue value = new PausingValue(true);
        value.set(true);
        MockVersionedValueListener<Boolean> owner = new MockVersionedValueListener<>(value);
        MockVersionedValueListener<Boolean> network = new MockVersionedValueListener<>(value);
        helperOwnerChangeNotLost(value, owner::setFromOwnerWithForceNotify, network);
        verify(owner.stub).update(true);
        verifyNoMoreInteractions(owner.stub);
    }

    @Test
    public void testUnownedSetNotLostToConcurrentSet() throws Exception {
        PausingValue value = new PausingValue(true);
        value.set(true);
        MockVersionedValueListener<Boolean> network = new MockVersionedValueListener<>(value);
        helperOwnerChangeNotLost(value, value::set, network);
    }

    /**
     * The owner changes the value, and before the change has reached a listener that applies
     * values from the network, that listener sets a newer value from the network. A listener
     * created with everyChange must still be told of the owner's change.
     * @param everyChange  how the network listener is created
     * @return the network listener
     */
    private MockVersionedValueListener<Boolean> helperOvertakenChange(boolean everyChange)
            throws InterruptedException {
        VersionedValue<Boolean> value = new VersionedValue<>(true);
        value.set(true); // leaves the default version
        @SuppressWarnings("unchecked")
        MockVersionedValueListener<Boolean>[] owner = new MockVersionedValueListener[1];
        @SuppressWarnings("unchecked")
        MockVersionedValueListener<Boolean>[] network = new MockVersionedValueListener[1];
        // Registered first, so it runs before the other listeners are told of the owner's
        // change; it lets the network set finish first.
        value.addPropertyChangeListener(e -> {
            if (((VersionedValue.Change) e).getOrigin() == owner[0]) {
                Thread other = new Thread(() -> network[0].setFromOwner(true));
                other.start();
                try {
                    other.join();
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        });
        network[0] = new MockVersionedValueListener<>(value, everyChange);
        owner[0] = new MockVersionedValueListener<>(value);

        owner[0].setFromOwner(false);

        Assert.assertTrue(value.getLatestData());
        verify(owner[0].stub).update(true);
        verifyNoMoreInteractions(owner[0].stub);
        return network[0];
    }

    @Test
    public void testEveryChangeListenerToldOfOvertakenChange() throws Exception {
        MockVersionedValueListener<Boolean> l = helperOvertakenChange(true);
        verify(l.stub).update(false);
        verifyNoMoreInteractions(l.stub);
    }

    @Test
    public void testDefaultListenerSkipsOvertakenChange() throws Exception {
        MockVersionedValueListener<Boolean> l = helperOvertakenChange(false);
        verifyNoMoreInteractions(l.stub);
    }

    @Test
    public void testEveryChangeListenerSkipsOwnChanges() throws Exception {
        v.set(33);
        MockVersionedValueListener<Integer> l1 = new MockVersionedValueListener<>(v, true);
        MockVersionedValueListener<Integer> l2 = new MockVersionedValueListener<>(v);

        l1.setFromOwner(13);
        verify(l2.stub).update(13);
        verifyNoMoreInteractions(l1.stub, l2.stub);
        reset(l1.stub, l2.stub);

        l2.setFromOwner(21);
        verify(l1.stub).update(21);
        verifyNoMoreInteractions(l1.stub, l2.stub);
        reset(l1.stub, l2.stub);

        // No notification when the value does not change, unless forced.
        l2.setFromOwner(21);
        verifyNoMoreInteractions(l1.stub, l2.stub);
        l2.setFromOwnerWithForceNotify(21);
        verify(l1.stub).update(21);
        verifyNoMoreInteractions(l1.stub, l2.stub);
        reset(l1.stub, l2.stub);

        v.set(5);
        verify(l1.stub).update(5);
        verify(l2.stub).update(5);
        verifyNoMoreInteractions(l1.stub, l2.stub);
    }

}
