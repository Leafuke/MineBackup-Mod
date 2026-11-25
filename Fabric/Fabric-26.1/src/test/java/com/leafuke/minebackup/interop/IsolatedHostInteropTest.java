package com.leafuke.minebackup.interop;

import com.google.gson.JsonObject;
import com.leafuke.minebackup.knotlink.sdk.SignalSubscriber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static com.leafuke.minebackup.interop.InteropWire.*;
import static org.junit.jupiter.api.Assertions.*;

/** Optional cross-product test against an already started fault-only Host and a private broker. */
@EnabledIfEnvironmentVariable(named="MINEBACKUP_INTEROP_PROBE_WORK",matches=".+")
class IsolatedHostInteropTest {
    @Test void javaClientFollowsActualBackendReceipts()throws Exception {
        Path work=Path.of(System.getenv("MINEBACKUP_INTEROP_PROBE_WORK")).toRealPath();
        assertTrue(Files.isRegularFile(work.resolve("acceptance-owner")));
        int query=Integer.parseInt(System.getenv("MINEBACKUP_INTEROP_PROBE_QUERY"));
        int subscriber=Integer.parseInt(System.getenv("MINEBACKUP_INTEROP_PROBE_SUBSCRIBE"));
        assertTrue(query>=49152&&query<=65535&&subscriber>=49152&&subscriber<=65535&&query!=subscriber);
        Path world=work.resolve("source");
        var completed=new ConcurrentHashMap<UUID,CompletableFuture<InteropSnapshot>>();
        var steps=new CopyOnWriteArrayList<String>();
        var freeze=new AtomicReference<UUID>();
        var reopened=new CountDownLatch(1);
        try(var lock=FileChannel.open(world.resolve("session.lock"),StandardOpenOption.READ);
            var link=new InteropClient(work.resolve("interop/endpoints"),work.resolve("mod-state"),
                    (instance,type,body,budget)->InteropWire.callAt(query,instance,type,body,budget),
                    (endpoint,w,messages,disconnect)->{
                        var s=new SignalSubscriber("frmc3","s."+endpoint.instance()+"."+w.session(),"127.0.0.1",subscriber,2000);
                        s.setSignalListener(messages::accept);s.setDisconnectListener(e->disconnect.run());s.start();
                        return new InteropClient.Subscription(){
                            public boolean connected(){return s.isRunning();}
                            public void close(){s.stop();}
                        };
                    }, new InteropClient.Listener(){
                        public void snapshot(InteropSnapshot snap,boolean visible) {
                            if(snap.result()!=null)completed.computeIfAbsent(snap.operation(),id->new CompletableFuture<>()).complete(snap);
                        }
                        public void unconfirmed(UUID id,boolean restore) {
                            completed.computeIfAbsent(id,k->new CompletableFuture<>()).completeExceptionally(new IllegalStateException("unconfirmed"));
                        }
                        public void actionAcknowledged(UUID id,String kind){if(kind.equals("open"))reopened.countDown();}
                        public CompletionStage<InteropSnapshot.Ack> perform(InteropClient.Binding b,InteropSnapshot s,long deadline,BooleanSupplier live) {
                            assertTrue(live.getAsBoolean());assertTrue(System.nanoTime()<deadline);
                            var step=s.step();steps.add(step.kind());
                            try{
                                return CompletableFuture.completedFuture(switch(step.kind()){
                                    case "saveAndFreeze"->{assertTrue(freeze.compareAndSet(null,step.id()));yield InteropSnapshot.Ack.of("frozen");}
                                    case "thaw"->{assertEquals(freeze.getAndSet(null),step.releases());yield new InteropSnapshot.Ack("thawed",true,null);}
                                    case "saveAndExit"->{lock.close();yield InteropSnapshot.Ack.of("released");}
                                    case "open"->{
                                        try(var permission=WorldAdmission.enter(work.resolve("interop/guards"),world,s.operation(),b.endpoint().domain())){
                                            assertNull(permission.id());
                                        }
                                        yield InteropSnapshot.Ack.of("opened");
                                    }
                                    default->throw new AssertionError(step.kind());
                                });
                            }catch(Exception failure){return CompletableFuture.failedFuture(failure);}
                        }
                    })){
            link.enter(world,false);
            for(int i=0;i<150&&link.binding()==null;i++)Thread.sleep(100);
            assertNotNull(link.binding(),()->link.status().toString());
            assertNotNull(link.binding().id());
            var first=execute(link,completed,"backup",object("comment","岩&*&矿"));
            assertEquals("created",string(first.result(),"effect"));
            var protectedResult=execute(link,completed,"backup",object("protect",true));
            assertTrue(bool(protectedResult.result(),"important"));
            assertTrue(bool(link.catalog("importance",object("targetRef",link.binding().choice().target(),
                    "backupRef",string(protectedResult.result(),"backupRef"))).get(5,TimeUnit.SECONDS).get(0).getAsJsonObject(),"important"));
            Files.writeString(world.resolve("marker.txt"),"after");
            var restored=execute(link,completed,"restore",object("backupRef",string(first.result(),"backupRef")));
            assertEquals(string(first.result(),"backupRef"),string(restored.result(),"backupRef"));
            assertTrue(reopened.await(15,TimeUnit.SECONDS),()->"No open receipt: "+steps+" / "+link.status());
            assertEquals("before",Files.readString(world.resolve("marker.txt")));
            assertEquals(1,Collections.frequency(steps,"saveAndExit"));
            assertEquals(1,Collections.frequency(steps,"open"));
            assertEquals(Collections.frequency(steps,"saveAndFreeze"),Collections.frequency(steps,"thaw"));
        }
    }
    private static InteropSnapshot execute(InteropClient link,Map<UUID,CompletableFuture<InteropSnapshot>> completed,String kind,JsonObject options)throws Exception{
        UUID op=UUID.randomUUID();var done=new CompletableFuture<InteropSnapshot>();completed.put(op,done);
        var ack=link.submit(op,kind,null,options).get(10,TimeUnit.SECONDS);
        var result=done.get(95,TimeUnit.SECONDS);
        assertEquals("succeeded",string(result.result(),"outcome"),()->result+" / ACK "+ack);
        return result;
    }
}
