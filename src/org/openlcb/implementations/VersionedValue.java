package org.openlcb.implementations;

import java.util.logging.Logger;

/**
 * A value with a version number, shared between owners, which change it, and listeners, which
 * are told of changes. Owners usually act through a {@link VersionedValueListener}.
 * <p>
 * <b>Threading.</b> Any thread may call any method. A set takes the lock of this object, takes
 * a new version or checks the one it was given, stores the value, and releases the lock before
 * the listeners are told. So:
 * <ul>
 * <li>values are stored in the order their versions are taken, and a set that takes its own
 * version ({@link #set(Object)}, {@link VersionedValueListener#setFromOwner}) is never refused,
 * whatever other threads do at the same time;</li>
 * <li>a set at a version requested earlier ({@link #set(int, Object)}) is refused if a newer
 * version has been stored since;</li>
 * <li>listeners are told without the lock held, so when sets are made on different threads at
 * the same time their notifications can arrive in a different order from their versions. Each
 * notification is a {@link Change} and carries its version. A {@link VersionedValueListener}
 * in its default mode reads the current value when it is notified and ignores anything older
 * than what it has already seen, so that order does not affect it; in its everyChange mode it is
 * told of each change in the order the notifications arrive;</li>
 * <li>listeners may call back into this object, including setting the value;</li>
 * <li>{@link #getLatestData()} and {@link #getVersion()} read without the lock.</li>
 * </ul>
 * <p>
 * Created by bracz on 12/30/15.
 */
public class VersionedValue<T> {
    T data;
    int version;
    int nextVersion;
    java.beans.PropertyChangeSupport pcs = new java.beans.PropertyChangeSupport(this);
    static int DEFAULT_VERSION = 1;
    private int defaultVersion = DEFAULT_VERSION;
    private final static Logger log = Logger.getLogger(VersionedValue.class.getCanonicalName());

    /**
     * Notification of one change of the value, sent to the property change listeners. Besides
     * the old and new value it carries the version the change was made at, and the listener
     * that made it.
     */
    public static final class Change extends java.beans.PropertyChangeEvent {
        private static final long serialVersionUID = 1L;
        private final int version;
        private final transient Object origin;

        Change(VersionedValue<?> source, Object oldValue, Object newValue, int version,
               Object origin) {
            super(source, "updated", oldValue, newValue);
            this.version = version;
            this.origin = origin;
        }

        /**
         * @return the version this change was made at.
         */
        public int getVersion() {
            return version;
        }

        /**
         * @return the VersionedValueListener that made this change, or null if it was not made
         * through a listener.
         */
        public Object getOrigin() {
            return origin;
        }
    }

    public VersionedValue(T t) {
        version = DEFAULT_VERSION;
        nextVersion = DEFAULT_VERSION + 1;
        data = t;
    }

    /**
     * @return a strictly monotonically increasing version number to be used for setting the value.
     */
    public int getNewVersion() {
        int newVersion;
        synchronized (this) {
            newVersion = ++nextVersion;
        }
        return newVersion;
    }

    /**
     * Resets the state of the listener to construction state, without changing the value. The
     * next 'set' will force updates to be called, and isVersionAtDefault will return true.
     */
    public synchronized void setVersionToDefault() {
        defaultVersion = version;
    }

    /**
     * @return true if we are currently at the default version (either just after construction or
     * due to ra eset to default call).
     */
    public synchronized boolean isVersionAtDefault() {
        return version == defaultVersion;
    }

    /**
     * Sets the current value; possibly calling listeners. Handles versioning internally.
     * <p>
     * The new version is taken and the value stored as one step, so a set made at the same time
     * on another thread cannot take a later version, be stored first, and cause this set to be
     * refused as out of date.
     * @param t new value of data stored.
     */
    public void set(T t) {
        Change change;
        synchronized (this) {
            change = store(getNewVersion(), t, false, null);
        }
        fire(change);
    }

    /**
     * Sets the value using a pre-requested version. The set has no effect if the version has
     * already been exceeded.
     * @param atVersion proposed new version number
     * @param t new value of data stored
     * @return true if data was updated. False if the version is already outdated and no change
     * was made.
     */
    public boolean setWithForceNotify(int atVersion, T t) {
        return setInternal(atVersion, t, true);
    }

    /**
     * Sets the value using a pre-requested version. The set has no effect if the version has
     * already been exceeded.
     * @param atVersion proposed new version number
     * @param t new value of data stored
     * @return true if data was updated. False if the version is already outdated and no change
     * was made.
     */
    public boolean set(int atVersion, T t) {
        return setInternal(atVersion, t, false);
    }

    private boolean setInternal(int atVersion, T t, boolean forceNotify) {
        Change change;
        synchronized (this) {
            if (atVersion <= version) return false;
            change = store(atVersion, t, forceNotify, null);
        }
        fire(change);
        return true;
    }

    /**
     * Stores a value at a version newer than the current one. The caller must hold the lock of
     * this object, and must pass the result to {@link #fire} once it has released the lock.
     * @param atVersion    new version number, greater than the current version
     * @param t            new value of data stored
     * @param forceNotify  notify the listeners even if the value has not changed
     * @param origin       listener making the change, or null
     * @return the notification to send, or null if there is none
     */
    Change store(int atVersion, T t, boolean forceNotify, Object origin) {
        int oldVersion = version;
        version = atVersion;
        if (nextVersion <= atVersion) {
            nextVersion = atVersion + 1;
        }
        if (data.equals(t) && oldVersion != defaultVersion && !forceNotify) {
            return null;
        }
        T old = data;
        if (oldVersion == defaultVersion || forceNotify) {
            old = null;
        }
        data = t;
        return new Change(this, old, t, atVersion, origin);
    }

    /**
     * Sends a notification returned by {@link #store} to the listeners. Must be called without
     * holding the lock of this object.
     * @param change notification to send, or null for none
     */
    void fire(Change change) {
        if (change != null) {
            pcs.firePropertyChange(change);
        }
    }

    /**
     * @return data stored at the current version.
     */
    public T getLatestData() {
        return data;
    }

    /**
     * @return current version number.
     */
    public int getVersion() {
        return version;
    }

    public synchronized T getDataForVersion(int expectedVersion) throws VersionOutOfDateException {
        if (version != expectedVersion) {
            throw new VersionOutOfDateException();
        }
        return data;
    }

    public synchronized void addPropertyChangeListener(java.beans.PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    public synchronized void removePropertyChangeListener(java.beans.PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }

    protected void firePropertyChange(String p, Object old, Object n) {
        pcs.firePropertyChange(p, old, n);
    }

}
