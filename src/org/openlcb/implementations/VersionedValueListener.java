package org.openlcb.implementations;

import org.openlcb.implementations.VersionedValue;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * Connects one owner to a {@link VersionedValue}. The owner calls {@link #setFromOwner} to change
 * the value, and {@link #update} is called when someone else changes it.
 * <p>
 * <b>Threading.</b> Any thread may call {@link #setFromOwner}; it takes a new version and stores
 * the value as one step under the value's lock, so it is never refused. {@link #update} is called
 * on the thread that made the change, with no lock of this class or of the value held, so it may
 * itself call {@link #setFromOwner}. In the default mode, {@link #update} is called with the
 * current value whenever the value's version is newer than any this listener has set or been
 * told of, so a change that a newer one has overtaken is not reported. In the everyChange mode
 * ({@link #VersionedValueListener(VersionedValue, boolean)}), {@link #update} is called for every
 * change made by someone other than this listener, with that change's value, in the order the
 * notifications arrive, which can differ from the version order when the changes were made on
 * different threads at the same time (see {@link VersionedValue}).
 * <p>
 * Created by bracz on 12/30/15.
 */
public abstract class VersionedValueListener<T> implements PropertyChangeListener {
    int ownerVersion;
    protected VersionedValue<T> parent;
    private final boolean everyChange;

    /**
     * Creates a listener that is told the latest value whenever someone else has set a value
     * newer than any this listener has set or been told of.
     * @param parent value to listen to
     */
    public VersionedValueListener(VersionedValue<T> parent) {
        this(parent, false);
    }

    /**
     * Creates a listener.
     * @param parent       value to listen to
     * @param everyChange  if false, as for {@link #VersionedValueListener(VersionedValue)},
     *                     update() is called with the latest value whenever someone else has set
     *                     a value newer than any this listener has set or been told of, so a
     *                     change already overtaken by a newer one is not reported. If true,
     *                     update() is called for every change made by someone else, with the
     *                     value of that change, even if this listener has set a newer value
     *                     since. This suits a listener that sends the owners' changes out to the
     *                     network and also sets the values it receives from the network: a value
     *                     received from the network must not stop an owner's earlier change from
     *                     being sent.
     */
    public VersionedValueListener(VersionedValue<T> parent, boolean everyChange) {
        this.parent = parent;
        this.everyChange = everyChange;
        parent.addPropertyChangeListener(this);
        ownerVersion = parent.getVersion();
    }

    /** Stops listening to changes of the parent. Call this before abandoning the instance. */
    public void release() {
        parent.removePropertyChangeListener(this);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void propertyChange(PropertyChangeEvent propertyChangeEvent) {
        if (everyChange && propertyChangeEvent instanceof VersionedValue.Change) {
            VersionedValue.Change change = (VersionedValue.Change) propertyChangeEvent;
            if (change.getOrigin() != this) {
                update((T) change.getNewValue());
            }
            return;
        }
        T data;
        synchronized (this) {
            synchronized (parent) {
                if (parent.version <= ownerVersion) {
                    return;
                }
                ownerVersion = parent.version;
                data = parent.data;
            }
        }
        update(data);
    }

    /**
     * Sets the value of the shared state as seen from this listener. Will skip callback to this
     * listener.
     * @param t new value
     */
    public void setFromOwner(T t) {
        setFromOwner(t, false);
    }

    /**
     * Sets the value of the shared state as seen from this listener. Will skip callback to this
     * listener. Will force callbacks to all other listeners, even if the state has not changed.
     * @param t new value
     */
    public void setFromOwnerWithForceNotify(T t) {
        setFromOwner(t, true);
    }

    /**
     * Takes a new version and stores the value at it as one step, so that a set made at the same
     * time on another thread cannot take a later version, be stored first, and cause this set
     * to be refused as out of date.
     * @param t            new value
     * @param forceNotify  notify the other listeners even if the value has not changed
     */
    private void setFromOwner(T t, boolean forceNotify) {
        VersionedValue.Change change;
        synchronized (this) {
            synchronized (parent) {
                ownerVersion = parent.getNewVersion();
                change = parent.store(ownerVersion, t, forceNotify, this);
            }
        }
        parent.fire(change);
    }

    /** Calls the updater with the latest data. */
    public void pingUpdater() {
        update(parent.getLatestData());
    }

    /** Called when the backend has to update its version.
     * @param t value as of the new version.
     * */
    public abstract void update(T t);
}
