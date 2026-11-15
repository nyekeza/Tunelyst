package com.tunelyst.player

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LibraryScanCoordinatorTest {
 @Test fun simultaneousScansShareOneQueryButLaterScansRunAgain() {
  val started=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger();val result=AtomicReference<List<Track>>()
  val one=listOf(Track(1,"A","B","C",1,1000))
  val owner=Thread { result.set(LibraryScanCoordinator.run { calls.incrementAndGet();started.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));one }) }
  owner.start();assertTrue(started.await(3,TimeUnit.SECONDS))
  val followerResult=AtomicReference<List<Track>>();val follower=Thread { followerResult.set(LibraryScanCoordinator.run { calls.incrementAndGet();emptyList() }) }
  follower.start();val until=System.currentTimeMillis()+3000;while(follower.state!=Thread.State.WAITING && System.currentTimeMillis()<until)Thread.yield()
  release.countDown();owner.join(3000);follower.join(3000)
  assertEquals(one,result.get());assertEquals(one,followerResult.get());assertEquals(1,calls.get())
  assertEquals(emptyList<Track>(),LibraryScanCoordinator.run { calls.incrementAndGet();emptyList() });assertEquals(2,calls.get())
 }
 @Test fun exceptionsDoNotPoisonNextScan() {
  try { LibraryScanCoordinator.run { throw SecurityException("permission") };fail("Expected failure") } catch (_:SecurityException) {}
  assertEquals(emptyList<Track>(),LibraryScanCoordinator.run { emptyList() })
 }
}
