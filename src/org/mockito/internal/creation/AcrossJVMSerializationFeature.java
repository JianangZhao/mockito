package org.mockito.internal.creation;

import org.mockito.cglib.proxy.Factory;
import org.mockito.internal.creation.jmock.ClassImposterizer;
import org.mockito.internal.util.MockUtil;
import org.mockito.internal.util.StringJoiner;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.mock.MockCreationSettings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This is responsible for serializing a mock, it is enabled if the mock is implementing
 * {@link Serializable}.
 *
 * <p>
 *     The way it works is to enable serialization via the {@link #enableSerializationAcrossJVM(MockCreationSettings)},
 *     if the mock settings is set to be serializable it will add the {@link AcrossJVMMockitoMockSerilizable} interface.
 *     This interface defines a the {@link AcrossJVMSerializationFeature.AcrossJVMMockitoMockSerilizable#writeReplace()}
 *     whose signature match the one that is looked by the standard Java serialization.
 * </p>
 *
 * <p>
 *     Then in the {@link MethodInterceptorFilter} of mockito, if the <code>writeReplace</code> method is called,
 *     it will use the custom implementation of this class {@link #writeReplace(Object)}. This method has a specific
 *     knowledge on how to serialize a mockito mock that is based on CGLIB.
 * </p>
 *
 * <p><strong>Only one instance per mock.</strong></p>
 *
 * not thread-safe prototype !!!!
 *
 * @author Brice Dutheil
 */
public class AcrossJVMSerializationFeature implements Serializable {
    private static final long serialVersionUID = 7411152578314420778L;
    private boolean currentlySerializing = false;
    volatile AtomicInteger returningProxyCounter = new AtomicInteger(0);
    volatile AtomicInteger returningWrapperCounter = new AtomicInteger(0);
    private Lock mutex = new ReentrantLock();

    public boolean isWriteReplace(Method method) {
        return  method.getReturnType() == Object.class
                && method.getParameterTypes().length == 0
                && method.getName().equals("writeReplace");
    }


    public Object writeReplace(Object proxy) throws ObjectStreamException {
        try {
            mutex.lock();
            // mark started flag // per thread, not per instance
            // temporary loosy hack to avoid stackoverflow
            if(isCurrentlySerializing()) {
                System.out.println("[" + returningProxyCounter.getAndIncrement() + "] retuning proxy");
                return proxy;
            }
            markMockAsSerializing();

            System.out.println("[" + returningWrapperCounter.getAndIncrement() + "] returning wrapper");

            return new AcrossJVMMockSerializationProxy(proxy);         // stackoverflow
        } catch (IOException ioe) {
            throw new NotSerializableException(proxy.getClass().getCanonicalName()); // TODO throw our own serialization exception
        } finally {
            // unmark
            unmarkMockAsSerializing();
            mutex.unlock();
        }
    }

    private void unmarkMockAsSerializing() {
        currentlySerializing = false;
    }

    private void markMockAsSerializing() {
        currentlySerializing = true;
    }

    private boolean isCurrentlySerializing() {
        return currentlySerializing;
    }


    public <T> void enableSerializationAcrossJVM(MockCreationSettings<T> settings) {
        // havin faith that this set is modifiable
        if (settings.isSerializable()) {
            settings.getExtraInterfaces().add(AcrossJVMMockitoMockSerilizable.class);
        }
    }


    /**
     * This is the serialization proxy that will encapsulate the real mock data.
     *
     * It will allow deserilization of the mock in another classloader/vm through custom deserilization ObjectInputStream
     */
    public static class AcrossJVMMockSerializationProxy implements Serializable {
        private static final long serialVersionUID = -7600267929109286514L;
        private byte[] serializedMock;
        private Class typeToMock;
        private Set<Class> extraInterfaces;


        public AcrossJVMMockSerializationProxy(Object proxy) throws IOException {
            MockCreationSettings mockSettings = new MockUtil().getMockHandler(proxy).getMockSettings();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new MockitoMockObjectOutputStream(out);

            objectOutputStream.writeObject(proxy);

            objectOutputStream.close();
            out.close();

            this.serializedMock = out.toByteArray();
            this.typeToMock = mockSettings.getTypeToMock();
            this.extraInterfaces = mockSettings.getExtraInterfaces();
        }


        private Object readResolve() throws ObjectStreamException {
            try {
                ByteArrayInputStream bis = new ByteArrayInputStream(serializedMock);
                ObjectInputStream mockitoMockObjectInputStream = new MockitoMockObjectInputStream(bis, typeToMock, extraInterfaces);

                Object object = mockitoMockObjectInputStream.readObject();

                bis.close();
                mockitoMockObjectInputStream.close();
                return object;
            } catch (IOException ioe) {
                throw new InvalidObjectException("For some reason mock cannot ve dematerialized : " + ioe.toString() + "\n" + StringJoiner.join(ioe.getStackTrace()));
            } catch (ClassNotFoundException cce) {
                throw new InvalidObjectException("For some reason Mockito Mock class cannot be found : " + cce.toString());
            }
        }
    }



    public static class MockitoMockObjectInputStream extends ObjectInputStream {
        private Class typeToMock;
        private Set<Class> extraInterfaces;

        public MockitoMockObjectInputStream(InputStream in, Class typeToMock, Set<Class> extraInterfaces) throws IOException {
            super(in) ;
            this.typeToMock = typeToMock;
            this.extraInterfaces = extraInterfaces;
            enableResolveObject(true);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            Object anObject = readObject();
            if ("MockitoProxyMarker".equals(anObject)) {
                ClassImposterizer.INSTANCE.canImposterise(typeToMock);
                ClassImposterizer.INSTANCE.setConstructorsAccessible(typeToMock, true);
                Class<?> proxyClass = ClassImposterizer.INSTANCE.createProxyClass(
                        typeToMock,
                        extraInterfaces.toArray(new Class[extraInterfaces.size()])
                );

                try {
                    new FieldSetter(desc, desc.getClass().getDeclaredField("name")).set(proxyClass.getCanonicalName());
                } catch (NoSuchFieldException e) {
                    e.printStackTrace();
                }

                return proxyClass;
            }

            return super.resolveClass(desc);
        }
    }




    public interface AcrossJVMMockitoMockSerilizable {
        public Object writeReplace() throws java.io.ObjectStreamException;
    }


    private static class MockitoMockObjectOutputStream extends ObjectOutputStream {
        public MockitoMockObjectOutputStream(ByteArrayOutputStream out) throws IOException {
            super(out);
        }

        @Override
        protected void annotateClass(Class<?> cl) throws IOException {
            if (Factory.class.isAssignableFrom(cl)) {
                writeObject("MockitoProxyMarker");
            } else {
                writeObject("");
            }
            // might be also useful later, for embedding classloader info maybe
        }
    }
}
