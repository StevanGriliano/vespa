// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.feed.perf;

import com.yahoo.concurrent.ThreadFactoryFactory;
import com.yahoo.document.Document;
import com.yahoo.document.DocumentId;
import com.yahoo.document.DocumentPut;
import com.yahoo.document.DocumentTypeManager;
import com.yahoo.document.DocumentUpdate;
import com.yahoo.document.json.JsonFeedReader;
import com.yahoo.document.json.JsonWriter;
import com.yahoo.document.serialization.DocumentDeserializer;
import com.yahoo.document.serialization.DocumentDeserializerFactory;
import com.yahoo.document.serialization.DocumentSerializer;
import com.yahoo.document.serialization.DocumentSerializerFactory;
import com.yahoo.document.serialization.DocumentWriter;
import com.yahoo.documentapi.messagebus.protocol.DocumentProtocol;
import com.yahoo.documentapi.messagebus.protocol.PutDocumentMessage;
import com.yahoo.documentapi.messagebus.protocol.RemoveDocumentMessage;
import com.yahoo.documentapi.messagebus.protocol.UpdateDocumentMessage;
import com.yahoo.io.GrowableByteBuffer;
import com.yahoo.messagebus.Error;
import com.yahoo.messagebus.Message;
import com.yahoo.messagebus.MessageBusParams;
import com.yahoo.messagebus.RPCMessageBus;
import com.yahoo.messagebus.Reply;
import com.yahoo.messagebus.ReplyHandler;
import com.yahoo.messagebus.SourceSession;
import com.yahoo.messagebus.SourceSessionParams;
import com.yahoo.messagebus.StaticThrottlePolicy;
import com.yahoo.messagebus.network.rpc.RPCNetworkParams;
import com.yahoo.messagebus.routing.Route;
import com.yahoo.vespaxmlparser.DocumentFeedOperation;
import com.yahoo.vespaxmlparser.DocumentUpdateFeedOperation;
import com.yahoo.vespaxmlparser.FeedReader;
import com.yahoo.vespaxmlparser.FeedOperation;
import com.yahoo.vespaxmlparser.RemoveFeedOperation;
import com.yahoo.vespaxmlparser.VespaXMLFeedReader;
import net.jpountz.xxhash.XXHashFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Simon Thoresen Hult
 */
public class SimpleFeeder implements ReplyHandler {

    private final static long REPORT_INTERVAL = TimeUnit.SECONDS.toMillis(10);
    private final static long HEADER_INTERVAL = REPORT_INTERVAL * 24;
    private final DocumentTypeManager docTypeMgr = new DocumentTypeManager();
    private final InputStream in;
    private final PrintStream out;
    private final RPCMessageBus mbus;
    private final SourceSession session;
    private final long startTime = System.currentTimeMillis();
    private final AtomicReference<Throwable> failure = new AtomicReference<>(null);
    private final AtomicLong numReplies = new AtomicLong(0);
    private long maxLatency = Long.MIN_VALUE;
    private long minLatency = Long.MAX_VALUE;
    private long nextHeader = startTime + HEADER_INTERVAL;
    private long nextReport = startTime + REPORT_INTERVAL;
    private long sumLatency = 0;
    private final int numThreads;
    private final Destination destination;

    public static void main(String[] args) throws Throwable {
        new SimpleFeeder(new FeederParams().parseArgs(args)).run().close();
    }

    private interface Destination {
        void send(FeedOperation op);
        void close() throws Exception;
    }

    private static class MbusDestination implements Destination {
        private final PrintStream err;
        private final Route route;
        private final SourceSession session;
        private final AtomicReference<Throwable> failure;
        MbusDestination(SourceSession session, Route route, AtomicReference<Throwable> failure, PrintStream err) {
            this.route = route;
            this.err = err;
            this.session = session;
            this.failure = failure;
        }
        public void send(FeedOperation op) {
            Message msg = newMessage(op);
            if (msg == null) {
                err.println("ignoring operation; " + op.getType());
                return;
            }
            msg.setContext(System.currentTimeMillis());
            msg.setRoute(route);
            try {
                Error err = session.sendBlocking(msg).getError();
                if (err != null) {
                    failure.set(new IOException(err.toString()));
                }
            } catch (InterruptedException e) {}
        }
        public void close() throws Exception {
            session.destroy();
        }
    }

    private static class JsonDestination implements Destination {
        private final OutputStream outputStream;
        private final DocumentWriter writer;
        private final AtomicLong numReplies;
        private final AtomicReference<Throwable> failure;
        private boolean isFirst = true;
        JsonDestination(OutputStream outputStream, AtomicReference<Throwable> failure, AtomicLong numReplies) {
            this.outputStream = outputStream;
            writer = new JsonWriter(outputStream);
            this.numReplies = numReplies;
            this.failure = failure;
            try {
                outputStream.write('[');
                outputStream.write('\n');
            } catch (IOException e) {
                failure.set(e);
            }
        }
        public void send(FeedOperation op) {
            if (op.getType() == FeedOperation.Type.DOCUMENT) {
                if (!isFirst) {
                    try {
                        outputStream.write(',');
                        outputStream.write('\n');
                    } catch (IOException e) {
                        failure.set(e);
                    }
                } else {
                    isFirst = false;
                }
                writer.write(op.getDocument());
            }
            numReplies.incrementAndGet();
        }
        public void close() throws Exception {
            outputStream.write('\n');
            outputStream.write(']');
            outputStream.close();
        }

    }

    static private final int NONE = 0;
    static private final int DOCUMENT = 1;
    static private final int UPDATE = 2;
    static private final int REMOVE = 3;
    private static class VespaV1Destination implements Destination {
        private final OutputStream outputStream;
        GrowableByteBuffer buffer = new GrowableByteBuffer(16384);
        ByteBuffer header = ByteBuffer.allocate(16);
        private final AtomicLong numReplies;
        private final AtomicReference<Throwable> failure;
        VespaV1Destination(OutputStream outputStream, AtomicReference<Throwable> failure, AtomicLong numReplies) {
            this.outputStream = outputStream;
            this.numReplies = numReplies;
            this.failure = failure;
            try {
                outputStream.write('V');
                outputStream.write('1');
            } catch (IOException e) {
                failure.set(e);
            }
        }
        public void send(FeedOperation op) {
            DocumentSerializer writer = DocumentSerializerFactory.createHead(buffer);
            int type = NONE;
            if (op.getType() == FeedOperation.Type.DOCUMENT) {
                writer.write(op.getDocument());
                type = DOCUMENT;
            } else if (op.getType() == FeedOperation.Type.UPDATE) {
                writer.write(op.getDocumentUpdate());
                type = UPDATE;
            } else if (op.getType() == FeedOperation.Type.REMOVE) {
                writer.write(op.getRemove());
                type = REMOVE;
            }
            int sz = buffer.position();
            long hash = hash(buffer.array(), 0, sz);
            try {

                header.putInt(sz);
                header.putInt(type);
                header.putLong(hash);
                outputStream.write(header.array(), 0, header.position());
                outputStream.write(buffer.array(), 0, buffer.position());
                header.clear();
                buffer.clear();
            } catch (IOException e) {
                failure.set(e);
            }
            numReplies.incrementAndGet();
        }
        public void close() throws Exception {
            outputStream.close();
        }
        static long hash(byte [] buf, int offset, int length) {
            return XXHashFactory.fastestJavaInstance().hash64().hash(buf, offset, length, 0);
        }
    }

    static class VespaV1FeedReader implements FeedReader {
        private final InputStream in;
        private final DocumentTypeManager mgr;
        private final byte[] prefix = new byte[16];
        VespaV1FeedReader(InputStream in, DocumentTypeManager mgr) throws IOException {
            this.in = in;
            this.mgr = mgr;
            byte [] header = new byte[2];
            int read = in.read(header);
            if ((read != header.length) || (header[0] != 'V') || (header[1] != '1')) {
                throw new IllegalArgumentException("Invalid Header " + Arrays.toString(header));
            }
        }

        class LazyDocumentOperation extends FeedOperation {
            private final DocumentDeserializer deserializer;
            LazyDocumentOperation(DocumentDeserializer deserializer) {
                super(Type.DOCUMENT);
                this.deserializer = deserializer;
            }

            @Override
            public Document getDocument() {
                return new Document(deserializer);
            }
        }
        class LazyUpdateOperation extends FeedOperation {
            private final DocumentDeserializer deserializer;
            LazyUpdateOperation(DocumentDeserializer deserializer) {
                super(Type.UPDATE);
                this.deserializer = deserializer;
            }

            @Override
            public DocumentUpdate getDocumentUpdate() {
                return new DocumentUpdate(deserializer);
            }
        }
        @Override
        public FeedOperation read() throws Exception {
            int read = in.read(prefix);
            if (read != prefix.length) {
                return FeedOperation.INVALID;
            }
            ByteBuffer header = ByteBuffer.wrap(prefix);
            int sz = header.getInt();
            int type = header.getInt();
            long hash = header.getLong();
            byte [] blob = new byte[sz];
            read = in.read(blob);
            if (read != blob.length) {
                throw new IllegalArgumentException("Underflow, failed reading " + blob.length + "bytes. Got " + read);
            }
            long computedHash = VespaV1Destination.hash(blob, 0, blob.length);
            if (computedHash != hash) {
                throw new IllegalArgumentException("Hash mismatch, expected " + hash + ", got " + computedHash);
            }
            DocumentDeserializer deser = DocumentDeserializerFactory.createHead(mgr, GrowableByteBuffer.wrap(blob));
            if (type == DOCUMENT) {
                return new LazyDocumentOperation(deser);
            } else if (type == UPDATE) {
                return new LazyUpdateOperation(deser);
            } else if (type == REMOVE) {
                return new RemoveFeedOperation(new DocumentId(deser));
            } else {
                throw new IllegalArgumentException("Unknown operation " + type);
            }
        }
    }

    private Destination createDumper(FeederParams params) {
        if (params.getDumpFormat() == FeederParams.DumpFormat.VESPA) {
            return new VespaV1Destination(params.getDumpStream(), failure, numReplies);
        }
        return new JsonDestination(params.getDumpStream(), failure, numReplies);
    }
    SimpleFeeder(FeederParams params) {
        in = params.getStdIn();
        out = params.getStdOut();
        numThreads = params.getNumDispatchThreads();
        mbus = newMessageBus(docTypeMgr, params.getConfigId());
        session = newSession(mbus, this, params.isSerialTransferEnabled());
        docTypeMgr.configure(params.getConfigId());
        destination = (params.getDumpStream() != null)
                ? createDumper(params)
                : new MbusDestination(session, params.getRoute(), failure, params.getStdErr());
    }

    private void sendOperation(FeedOperation op) {
        destination.send(op);
    }

    SourceSession getSourceSession() { return session; }
    private FeedReader createFeedReader() throws Exception {
        in.mark(8);
        byte [] b = new byte[2];
        int numRead = in.read(b);
        in.reset();
        if (numRead != b.length) {
            throw new IllegalArgumentException("Need to read " + b.length + " bytes to detect format. Got " + numRead + " bytes.");
        }
        if (b[0] == '[') {
            return new JsonFeedReader(in, docTypeMgr);
        } else if ((b[0] == 'V') && (b[1] == '1')) {
            return new VespaV1FeedReader(in, docTypeMgr);
        } else {
             return new VespaXMLFeedReader(in, docTypeMgr);
        }
    }

    SimpleFeeder run() throws Throwable {
        ExecutorService executor = (numThreads > 1)
                ? new ThreadPoolExecutor(numThreads, numThreads, 0L, TimeUnit.SECONDS,
                                         new SynchronousQueue<>(false),
                                         ThreadFactoryFactory.getDaemonThreadFactory("perf-feeder"),
                                         new ThreadPoolExecutor.CallerRunsPolicy())
                : null;
        FeedReader reader = createFeedReader();

        printHeader();
        long numMessages = 0;
        while (failure.get() == null) {
            FeedOperation op = reader.read();
            if (op.getType() == FeedOperation.Type.INVALID) {
                break;
            }
            if (executor != null) {
                executor.execute(() -> sendOperation(op));
            } else {
                sendOperation(op);
            }
            ++numMessages;
        }
        while (failure.get() == null && numReplies.get() < numMessages) {
            Thread.sleep(100);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        printReport();
        return this;
    }

    void close() throws Exception {
        destination.close();
        mbus.destroy();
    }

    private static Message newMessage(FeedOperation op) {
        switch (op.getType()) {
        case DOCUMENT: {
            PutDocumentMessage message = new PutDocumentMessage(new DocumentPut(op.getDocument()));
            message.setCondition(op.getCondition());
            return message;
        }
        case REMOVE: {
            RemoveDocumentMessage message = new RemoveDocumentMessage(op.getRemove());
            message.setCondition(op.getCondition());
            return message;
        }
        case UPDATE: {
            UpdateDocumentMessage message = new UpdateDocumentMessage(op.getDocumentUpdate());
            message.setCondition(op.getCondition());
            return message;
        }
        default:
            return null;
        }
    }

    @Override
    public void handleReply(Reply reply) {
        if (failure.get() != null) {
            return;
        }
        if (reply.hasErrors()) {
            failure.compareAndSet(null, new IOException(formatErrors(reply)));
            return;
        }
        long now = System.currentTimeMillis();
        long latency = now - (long) reply.getContext();
        numReplies.incrementAndGet();
        accumulateReplies(now, latency);
    }
    private synchronized void accumulateReplies(long now, long latency) {
        minLatency = Math.min(minLatency, latency);
        maxLatency = Math.max(maxLatency, latency);
        sumLatency += latency;
        if (now > nextHeader) {
            printHeader();
            nextHeader += HEADER_INTERVAL;
        }
        if (now > nextReport) {
            printReport();
            nextReport += REPORT_INTERVAL;
        }
    }

    private void printHeader() {
        out.println("total time, num messages, min latency, avg latency, max latency");
    }

    private void printReport() {
        out.format("%10d, %12d, %11d, %11d, %11d\n", System.currentTimeMillis() - startTime,
                   numReplies.get(), minLatency, sumLatency / numReplies.get(), maxLatency);
    }

    private static String formatErrors(Reply reply) {
        StringBuilder out = new StringBuilder();
        out.append(reply.getMessage().toString()).append('\n');
        for (int i = 0, len = reply.getNumErrors(); i < len; ++i) {
            out.append(reply.getError(i).toString()).append('\n');
        }
        return out.toString();
    }

    private static RPCMessageBus newMessageBus(DocumentTypeManager docTypeMgr, String configId) {
        return new RPCMessageBus(new MessageBusParams().addProtocol(new DocumentProtocol(docTypeMgr)),
                                 new RPCNetworkParams().setSlobrokConfigId(configId),
                                 configId);
    }

    private static SourceSession newSession(RPCMessageBus mbus, ReplyHandler replyHandler, boolean serial) {
        SourceSessionParams params = new SourceSessionParams();
        params.setReplyHandler(replyHandler);
        if (serial) {
            params.setThrottlePolicy(new StaticThrottlePolicy().setMaxPendingCount(1));
        }
        return mbus.getMessageBus().createSourceSession(params);
    }
}
