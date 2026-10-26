package org.openlcb.implementations;

import static org.mockito.Mockito.mock;

/**
 * Created by bracz on 1/9/16.
 */
public class MockVersionedValueListener<T> extends VersionedValueListener<T> {
    public MockVersionedValueListener(VersionedValue<T> parent) {
        super(parent);
    }

    public MockVersionedValueListener(VersionedValue<T> parent, boolean everyChange) {
        super(parent, everyChange);
    }

    public class UpdaterStub {
        public void update(T t) {}
    }

    UpdaterStub stub = mock(UpdaterStub.class);

    @Override
    public void update(T t) {
        stub.update(t);
    }
}
