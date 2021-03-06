/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.mapstore;

import com.hazelcast.config.Config;
import com.hazelcast.config.GroupConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapStoreConfig;
import com.hazelcast.config.MapStoreConfig.InitialLoadMode;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.EntryAdapter;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.MapLoader;
import com.hazelcast.core.MapLoaderLifecycleSupport;
import com.hazelcast.core.MapStore;
import com.hazelcast.core.MapStoreAdapter;
import com.hazelcast.core.MapStoreFactory;
import com.hazelcast.core.PostProcessingMapStore;
import com.hazelcast.instance.GroupProperties;
import com.hazelcast.instance.TestUtil;
import com.hazelcast.map.AbstractEntryProcessor;
import com.hazelcast.map.impl.MapContainer;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.MapStoreWrapper;
import com.hazelcast.map.impl.RecordStore;
import com.hazelcast.map.impl.proxy.MapProxyImpl;
import com.hazelcast.map.mapstore.MapStoreWriteBehindTest.RecordingMapStore;
import com.hazelcast.monitor.LocalMapStats;
import com.hazelcast.query.SampleObjects.Employee;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.test.AssertTask;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.QuickTest;

import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;


/**
 * @author enesakar 1/21/13
 */
@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class MapStoreTest extends HazelcastTestSupport {

    @Test(timeout = 120000)
    public void testMapGetAll() throws InterruptedException {

        final Map<String, String> _map = new HashMap<String, String>();
        _map.put("key1", "value1");
        _map.put("key2", "value2");
        _map.put("key3", "value3");

        final AtomicBoolean loadAllCalled = new AtomicBoolean(false);
        final AtomicBoolean loadCalled = new AtomicBoolean(false);

        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        Config cfg = new Config();
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setImplementation(new MapLoader<String, String>() {

            public String load(String key) {
                loadCalled.set(true);
                return _map.get(key);
            }

            public Map<String, String> loadAll(Collection<String> keys) {
                loadAllCalled.set(true);
                final HashMap<String, String> temp = new HashMap<String, String>();
                for (String key : keys) {
                    temp.put(key, _map.get(key));
                }
                return temp;
            }

            public Set<String> loadAllKeys() {
                return _map.keySet();
            }
        });
        cfg.getMapConfig("testMapGetAll").setMapStoreConfig(mapStoreConfig);

        HazelcastInstance instance1 = nodeFactory.newHazelcastInstance(cfg);
        HazelcastInstance instance2 = nodeFactory.newHazelcastInstance(cfg);

        IMap map = instance1.getMap("testMapGetAll");

        final HashSet<String> keys = new HashSet<String>(3);
        keys.add("key1");
        keys.add("key3");
        keys.add("key4");

        final Map subMap = map.getAll(keys);
        assertEquals(2, subMap.size());
        assertEquals("value1", subMap.get("key1"));
        assertEquals("value3", subMap.get("key3"));

        assertTrue(loadAllCalled.get());
        assertFalse(loadCalled.get());
    }

    @Test(timeout = 120000)
    public void testSlowStore() throws Exception {
        final TestMapStore store = new WaitingOnFirstTestMapStore();
        Config cfg = new Config();
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setWriteDelaySeconds(1);
        mapStoreConfig.setImplementation(store);
        cfg.getMapConfig("default").setMapStoreConfig(mapStoreConfig);
        HazelcastInstance h1 = createHazelcastInstance(cfg);
        final IMap<Integer, Integer> map = h1.getMap("testSlowStore");
        int count = 1000;
        for (int i = 0; i < count; i++) {
            map.put(i, 1);
        }
        Thread.sleep(2000); // sleep for scheduling following puts to a different second
        for (int i = 0; i < count; i++) {
            map.put(i, 2);
        }
        for (int i = 0; i < count; i++) {
            final int index = i;
            assertTrueEventually(new AssertTask() {
                @Override
                public void run() throws Exception {
                    final Integer valueInMap = map.get(index);
                    final Integer valueInStore = (Integer) store.getStore().get(index);

                    assertEquals(valueInMap, valueInStore);
                }
            });

        }
    }

    @Test(timeout = 120000)
    public void testInitialLoadModeEager() {
        int size = 10000;
        String mapName = randomMapName();
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);

        Config cfg = new Config();
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setImplementation(new SimpleMapLoader(size, true));
        mapStoreConfig.setInitialLoadMode(MapStoreConfig.InitialLoadMode.EAGER);
        cfg.getMapConfig(mapName).setMapStoreConfig(mapStoreConfig);

        HazelcastInstance instance1 = nodeFactory.newHazelcastInstance(cfg);
        HazelcastInstance instance2 = nodeFactory.newHazelcastInstance(cfg);

        IMap map = instance1.getMap(mapName);

        assertSizeEventually(size, map);
    }

    @Test(timeout = 120000)
    public void testInitialLoadModeEagerMultipleThread() {
        final int instanceCount = 2;
        final int size = 10000;
        final TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(instanceCount);
        final CountDownLatch countDownLatch = new CountDownLatch(instanceCount - 1);
        final Config cfg = new Config();
        GroupConfig groupConfig = new GroupConfig("testEager");
        cfg.setGroupConfig(groupConfig);
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setImplementation(new SimpleMapLoader(size, true));
        mapStoreConfig.setInitialLoadMode(MapStoreConfig.InitialLoadMode.EAGER);
        cfg.getMapConfig("testInitialLoadModeEagerMultipleThread").setMapStoreConfig(mapStoreConfig);

        HazelcastInstance instance1 = nodeFactory.newHazelcastInstance(cfg);
        Runnable runnable = new Runnable() {
            public void run() {
                HazelcastInstance instance2 = nodeFactory.newHazelcastInstance(cfg);
                final IMap<Object, Object> map = instance2.getMap("testInitialLoadModeEagerMultipleThread");
                assertEquals(size, map.size());
                countDownLatch.countDown();
            }
        };
        new Thread(runnable).start();

        assertOpenEventually(countDownLatch, 120);
        IMap map = instance1.getMap("testInitialLoadModeEagerMultipleThread");
        assertEquals(size, map.size());

    }

    @Test(timeout = 120000)
    public void testInitialLoadModeEagerWhileStoppigOneNode() throws InterruptedException {
        final int instanceCount = 2;
        final int size = 10000;
        final TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(instanceCount);
        final CountDownLatch countDownLatch = new CountDownLatch(instanceCount - 1);
        final Config cfg = new Config();
        GroupConfig groupConfig = new GroupConfig("testEager");
        cfg.setGroupConfig(groupConfig);
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setImplementation(new SimpleMapLoader(size, true));
        mapStoreConfig.setInitialLoadMode(MapStoreConfig.InitialLoadMode.EAGER);
        cfg.getMapConfig("testInitialLoadModeEagerWhileStoppigOneNode").setMapStoreConfig(mapStoreConfig);
        final HazelcastInstance instance1 = nodeFactory.newHazelcastInstance(cfg);
        final HazelcastInstance instance2 = nodeFactory.newHazelcastInstance(cfg);
        new Thread(new Runnable() {
            @Override
            public void run() {
                sleepSeconds(3);
                instance1.getLifecycleService().shutdown();
                sleepSeconds(3);
                final IMap<Object, Object> map = instance2.getMap("testInitialLoadModeEagerWhileStoppigOneNode");
                assertEquals(size, map.size());
                countDownLatch.countDown();

            }
        }).start();

        assertOpenEventually(countDownLatch);

        final IMap<Object, Object> map2 = instance2.getMap("testInitialLoadModeEagerWhileStoppigOneNode");
        final int map2Size = map2.size();
        assertEquals(size, map2Size);
    }

    @Test(timeout = 240000)
    public void testMapInitialLoad() throws InterruptedException {
        int size = 10000;
        String mapName = randomMapName();
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(3);

        Config cfg = new Config();
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setImplementation(new SimpleMapLoader(size, true));

        MapConfig mc = cfg.getMapConfig(mapName);
        mc.setMapStoreConfig(mapStoreConfig);

        HazelcastInstance instance1 = nodeFactory.newHazelcastInstance(cfg);
        HazelcastInstance instance2 = nodeFactory.newHazelcastInstance(cfg);
        IMap map = instance1.getMap(mapName);

        assertSizeEventually(size, map);
        for (int i = 0; i < size; i++) {
            assertEquals(i, map.get(i));
        }

        assertNull(map.put(size, size));
        assertEquals(size, map.remove(size));
        assertNull(map.get(size));

        HazelcastInstance instance3 = nodeFactory.newHazelcastInstance(cfg);
        for (int i = 0; i < size; i++) {
            assertEquals(i, map.get(i));
        }
    }

    @Test(timeout = 120000)
    public void issue614() {
        final ConcurrentMap<Long, String> STORE = new ConcurrentHashMap<Long, String>();
        STORE.put(1l, "Event1");
        STORE.put(2l, "Event2");
        STORE.put(3l, "Event3");
        STORE.put(4l, "Event4");
        STORE.put(5l, "Event5");
        STORE.put(6l, "Event6");
        Config config = new Config();
        config.getMapConfig("map")
                .setMapStoreConfig(new MapStoreConfig()
                        .setWriteDelaySeconds(1)
                        .setImplementation(new SimpleMapStore<Long, String>(STORE)));
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(3);
        HazelcastInstance h = nodeFactory.newHazelcastInstance(config);
        IMap map = h.getMap("map");
        Collection collection = map.values();
        LocalMapStats localMapStats = map.getLocalMapStats();
        assertEquals(0, localMapStats.getDirtyEntryCount());
    }

    @Test(timeout = 120000)
    public void testIssue583MapReplaceShouldTriggerMapStore() {
        final ConcurrentMap<String, Long> store = new ConcurrentHashMap<String, Long>();
        final MapStore<String, Long> myMapStore = new SimpleMapStore<String, Long>(store);
        Config config = new Config();
        config
                .getMapConfig("myMap")
                .setMapStoreConfig(new MapStoreConfig()
                        .setImplementation(myMapStore));
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(3);
        HazelcastInstance hc = nodeFactory.newHazelcastInstance(config);
        IMap<String, Long> myMap = hc.getMap("myMap");
        myMap.put("one", 1L);
        assertEquals(1L, myMap.get("one").longValue());
        assertEquals(1L, store.get("one").longValue());
        myMap.putIfAbsent("two", 2L);
        assertEquals(2L, myMap.get("two").longValue());
        assertEquals(2L, store.get("two").longValue());
        myMap.putIfAbsent("one", 5L);
        assertEquals(1L, myMap.get("one").longValue());
        assertEquals(1L, store.get("one").longValue());
        myMap.replace("one", 1L, 111L);
        assertEquals(111L, myMap.get("one").longValue());
        assertEquals(111L, store.get("one").longValue());
        myMap.replace("one", 1L);
        assertEquals(1L, myMap.get("one").longValue());
        assertEquals(1L, store.get("one").longValue());
    }

    @Test(timeout = 120000)
    public void issue587CallMapLoaderDuringRemoval() {
        final AtomicInteger loadCount = new AtomicInteger(0);
        final AtomicInteger storeCount = new AtomicInteger(0);
        final AtomicInteger deleteCount = new AtomicInteger(0);
        class SimpleMapStore2 extends SimpleMapStore<String, Long> {

            SimpleMapStore2(ConcurrentMap<String, Long> store) {
                super(store);
            }

            public Long load(String key) {
                loadCount.incrementAndGet();
                return super.load(key);
            }

            public void store(String key, Long value) {
                storeCount.incrementAndGet();
                super.store(key, value);
            }

            public void delete(String key) {
                deleteCount.incrementAndGet();
                super.delete(key);
            }
        }
        final ConcurrentMap<String, Long> store = new ConcurrentHashMap<String, Long>();
        final MapStore<String, Long> myMapStore = new SimpleMapStore2(store);
        Config config = new Config();
        config
                .getMapConfig("myMap")
                .setMapStoreConfig(new MapStoreConfig()
//                        .setWriteDelaySeconds(1)
                        .setImplementation(myMapStore));
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(3);
        HazelcastInstance hc = nodeFactory.newHazelcastInstance(config);
        store.put("one", 1l);
        store.put("two", 2l);
        assertEquals(0, loadCount.get());
        assertEquals(0, storeCount.get());
        assertEquals(0, deleteCount.get());
        IMap<String, Long> myMap = hc.getMap("myMap");
        assertEquals(1l, myMap.get("one").longValue());
        assertEquals(2l, myMap.get("two").longValue());
//        assertEquals(2, loadCount.get());
        assertEquals(0, storeCount.get());
        assertEquals(0, deleteCount.get());
        assertNull(myMap.remove("ten"));
//        assertEquals(3, loadCount.get());
        assertEquals(0, storeCount.get());
        assertEquals(0, deleteCount.get());
        myMap.put("three", 3L);
        myMap.put("four", 4L);
//        assertEquals(5, loadCount.get());
        assertEquals(2, storeCount.get());
        assertEquals(0, deleteCount.get());
        myMap.remove("one");
        assertEquals(2, storeCount.get());
        assertEquals(1, deleteCount.get());
//        assertEquals(5, loadCount.get());
    }

    @Test(timeout = 120000)
    public void testOneMemberFlush() throws Exception {
        TestMapStore testMapStore = new TestMapStore(1, 1, 1);
        testMapStore.setLoadAllKeys(false);
        int size = 100;
        Config config = newConfig(testMapStore, 200);
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(3);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        IMap map = h1.getMap("default");
        assertEquals(0, map.size());
        for (int i = 0; i < size; i++) {
            map.put(i, i);
        }
        assertEquals(size, map.size());
        assertEquals(0, testMapStore.getStore().size());
        assertEquals(size, map.getLocalMapStats().getDirtyEntryCount());
        map.flush();
        assertEquals(size, testMapStore.getStore().size());
        assertEquals(0, map.getLocalMapStats().getDirtyEntryCount());
        assertEquals(size, map.size());

        for (int i = 0; i < size / 2; i++) {
            map.remove(i);
        }
        assertEquals(size / 2, map.size());
        assertEquals(size, testMapStore.getStore().size());
        map.flush();
        assertEquals(size / 2, testMapStore.getStore().size());
        assertEquals(size / 2, map.size());
    }

    @Test(timeout = 120000)
    public void testOneMemberFlushOnShutdown() throws Exception {
        TestMapStore testMapStore = new TestMapStore(1, 1, 1);
        testMapStore.setLoadAllKeys(false);
        Config config = newConfig(testMapStore, 200);
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(3);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        IMap map1 = h1.getMap("default");
        assertEquals(0, map1.size());
        for (int i = 0; i < 100; i++) {
            map1.put(i, i);
        }
        assertEquals(100, map1.size());
        assertEquals(0, testMapStore.getStore().size());
        h1.getLifecycleService().shutdown();
        assertEquals(100, testMapStore.getStore().size());
        assertEquals(1, testMapStore.getDestroyCount());
    }

    @Test(timeout = 120000)
    public void testGetAllKeys() throws Exception {
        TestEventBasedMapStore testMapStore = new TestEventBasedMapStore();
        Map store = testMapStore.getStore();
        Set keys = new HashSet();
        int size = 1000;
        for (int i = 0; i < size; i++) {
            store.put(i, "value" + i);
            keys.add(i);
        }
        Config config = newConfig(testMapStore, 2);

        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(3);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        HazelcastInstance h2 = nodeFactory.newHazelcastInstance(config);

        IMap map1 = h1.getMap("default");
        IMap map2 = h2.getMap("default");

        //checkIfMapLoaded("default", h1);
        //checkIfMapLoaded("default", h2);

        assertEquals("value1", map1.get(1));
        assertEquals("value1", map2.get(1));

        assertEquals(1000, map1.size());
        assertEquals(1000, map2.size());

        HazelcastInstance h3 = nodeFactory.newHazelcastInstance(config);
        IMap map3 = h3.getMap("default");
        //checkIfMapLoaded("default", h3);

        assertEquals("value1", map1.get(1));
        assertEquals("value1", map2.get(1));
        assertEquals("value1", map3.get(1));

        assertEquals(1000, map1.size());
        assertEquals(1000, map2.size());
        assertEquals(1000, map3.size());

        h3.shutdown();

        assertEquals("value1", map1.get(1));
        assertEquals("value1", map2.get(1));

        assertEquals(1000, map1.size());
        assertEquals(1000, map2.size());
    }

    private boolean checkIfMapLoaded(String mapName, HazelcastInstance instance) throws InterruptedException {
        NodeEngineImpl nodeEngine = TestUtil.getNode(instance).nodeEngine;
        int partitionCount = nodeEngine.getPartitionService().getPartitionCount();
        MapService service = nodeEngine.getService(MapService.SERVICE_NAME);
        boolean loaded = false;

        final long end = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);

        while (!loaded) {
            for (int i = 0; i < partitionCount; i++) {
                final RecordStore recordStore = service.getMapServiceContext()
                        .getPartitionContainer(i).getRecordStore(mapName);
                if (recordStore != null) {
                    loaded = recordStore.isLoaded();
                    if (!loaded) {
                        break;
                    }
                }
            }
            if (System.currentTimeMillis() >= end) {
                break;
            }
            //give a rest to cpu.
            Thread.sleep(10);
        }

        return loaded;
    }

    /*
     * Test for Issue 572
    */
    @Test(timeout = 120000)
    public void testMapstoreDeleteOnClear() throws Exception {
        Config config = new Config();
        SimpleMapStore store = new SimpleMapStore();
        config.getMapConfig("testMapstoreDeleteOnClear").setMapStoreConfig(new MapStoreConfig().setEnabled(true).setImplementation(store));
        HazelcastInstance hz = createHazelcastInstance(config);
        IMap<Object, Object> map = hz.getMap("testMapstoreDeleteOnClear");
        int size = 10;
        for (int i = 0; i < size; i++) {
            map.put(i, i);
        }
        assertEquals(size, map.size());
        assertEquals(size, store.store.size());
        assertEquals(size, store.loadAllKeys().size());
        map.clear();
        assertEquals(0, map.size());
        assertEquals(0, store.loadAllKeys().size());
    }

    // bug: store is called twice on loadAll
    @Test(timeout = 120000)
    public void testIssue1070() {
        final String mapName = randomMapName();
        final Config config = new Config();
        final MapConfig mapConfig = config.getMapConfig(mapName);
        final MapStoreConfig mapStoreConfig = new MapStoreConfig();
        final NoDuplicateMapStore myMapStore = new NoDuplicateMapStore();
        final MapStoreConfig implementation = mapStoreConfig.setImplementation(myMapStore);
        mapConfig.setMapStoreConfig(implementation);

        myMapStore.store.put(1, 2);

        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance instance1 = nodeFactory.newHazelcastInstance(config);
        HazelcastInstance instance2 = nodeFactory.newHazelcastInstance(config);

        IMap<Object, Object> map = instance1.getMap(mapName);
        for (int i = 0; i < 271; i++) {
            map.get(i);
        }
        assertFalse(myMapStore.failed);
    }


    @Test(timeout = 120000)
    public void testIssue806CustomTTLForNull() {
        final ConcurrentMap<String, String> store = new ConcurrentHashMap<String, String>();
        final MapStore<String, String> myMapStore = new SimpleMapStore<String, String>(store);
        Config config = new Config();
        config
                .getMapConfig("testIssue806CustomTTLForNull")
                .setMapStoreConfig(new MapStoreConfig()
                        .setImplementation(myMapStore));
        HazelcastInstance hc = createHazelcastInstance(config);
        IMap<Object, Object> map = hc.getMap("testIssue806CustomTTLForNull");
        map.get("key");
        assertNull(map.get("key"));
        store.put("key", "value");
        assertEquals("value", map.get("key"));
    }

    @Test(timeout = 120000)
    public void testIssue991EvictedNullIssue() throws InterruptedException {
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setImplementation(new MapLoader<String, String>() {
            @Override
            public String load(String key) {
                return null;
            }

            @Override
            public Map<String, String> loadAll(Collection<String> keys) {
                return null;
            }

            @Override
            public Set<String> loadAllKeys() {
                return null;
            }
        });
        Config config = new Config();
        config
                .getMapConfig("testIssue991EvictedNullIssue")
                .setMapStoreConfig(mapStoreConfig);
        HazelcastInstance hc = createHazelcastInstance(config);
        IMap<Object, Object> map = hc.getMap("testIssue991EvictedNullIssue");
        map.get("key");
        assertNull(map.get("key"));
        map.put("key", "value");
        Thread.sleep(2000);
        assertEquals("value", map.get("key"));
    }

    @Test(timeout = 120000)
    @Ignore // see https://github.com/hazelcast/hazelcast/issues/2409
    public void testIssue1019() throws InterruptedException {
        final String keyWithNullValue = "keyWithNullValue";

        TestEventBasedMapStore testMapStore = new TestEventBasedMapStore() {
            @Override
            public Set loadAllKeys() {
                Set keys = new HashSet(super.loadAllKeys());
                // Include an extra key that will *not* be returned by loadAll().
                keys.add(keyWithNullValue);
                return keys;
            }
        };

        Map mapForStore = new HashMap();
        mapForStore.put("key1", 17);
        mapForStore.put("key2", 37);
        mapForStore.put("key3", 47);
        testMapStore.getStore().putAll(mapForStore);

        Config config = newConfig(testMapStore, 0);
        HazelcastInstance instance = createHazelcastInstance(config);
        IMap map = instance.getMap("default");

        Set expected = map.keySet();
        Set actual = mapForStore.keySet();
        assertEquals(expected, actual);
        assertEquals(map.values(), mapForStore.values());
        assertEquals(map.entrySet(), mapForStore.entrySet());

        assertFalse(map.containsKey(keyWithNullValue));
        assertNull(map.get(keyWithNullValue));
    }


    @Test(timeout = 120000)
    public void testIssue1115EnablingMapstoreMutatingValue() throws InterruptedException {
        Config cfg = new Config();
        String mapName = "testIssue1115";
        MapStore mapStore = new ProcessingStore();
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setImplementation(mapStore);
        cfg.getMapConfig(mapName).setMapStoreConfig(mapStoreConfig);
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance instance1 = nodeFactory.newHazelcastInstance(cfg);
        HazelcastInstance instance2 = nodeFactory.newHazelcastInstance(cfg);
        IMap<Integer, Employee> map = instance1.getMap(mapName);
        Random random = new Random();
        // testing put with new object
        for (int i = 0; i < 10; i++) {
            Employee emp = new Employee();
            emp.setAge(random.nextInt(20) + 20);
            map.put(i, emp);
        }

        for (int i = 0; i < 10; i++) {
            Employee employee = map.get(i);
            assertEquals(employee.getAge() * 1000, employee.getSalary(), 0);
        }

        // testing put with existing object
        for (int i = 0; i < 10; i++) {
            Employee emp = map.get(i);
            emp.setAge(random.nextInt(20) + 20);
            map.put(i, emp);
        }

        for (int i = 0; i < 10; i++) {
            Employee employee = map.get(i);
            assertEquals(employee.getAge() * 1000, employee.getSalary(), 0);
        }

        // testing put with replace
        for (int i = 0; i < 10; i++) {
            Employee emp = map.get(i);
            emp.setAge(random.nextInt(20) + 20);
            map.replace(i, emp);
        }

        for (int i = 0; i < 10; i++) {
            Employee employee = map.get(i);
            assertEquals(employee.getAge() * 1000, employee.getSalary(), 0);
        }

        // testing put with putIfAbsent
        for (int i = 10; i < 20; i++) {
            Employee emp = new Employee();
            emp.setAge(random.nextInt(20) + 20);
            map.putIfAbsent(i, emp);
        }

        for (int i = 10; i < 20; i++) {
            Employee employee = map.get(i);
            assertEquals(employee.getAge() * 1000, employee.getSalary(), 0);
        }

    }

    /**
     * Test for issue https://github.com/hazelcast/hazelcast/issues/1110
     */
    @Test(timeout = 300000)
    public void testMapLoader_withMapLoadChunkSize() throws InterruptedException {
        final int chunkSize = 5;
        final int numberOfEntriesToLoad = 100;
        final String mapName = randomString();

        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);

        ChunkedLoader chunkedLoader = new ChunkedLoader(numberOfEntriesToLoad, false);
        Config cfg = createChunkedMapLoaderConfig(mapName, chunkSize, chunkedLoader);

        HazelcastInstance node = nodeFactory.newHazelcastInstance(cfg);

        IMap map = node.getMap(mapName);

        final CountDownLatch latch = new CountDownLatch(numberOfEntriesToLoad);
        map.addEntryListener(new EntryAdapter() {
            @Override
            public void entryAdded(EntryEvent event) {
                latch.countDown();
            }
        }, true);
        // force creation of all partition record-stores.
        map.size();

        //await finish of map load.
        assertOpenEventually(latch, 240);

        final int expectedChunkCount = numberOfEntriesToLoad / chunkSize;
        final int actualChunkCount = chunkedLoader.numberOfChunks.get();
        assertEquals(expectedChunkCount, actualChunkCount);

        assertEquals(numberOfEntriesToLoad, map.size());
    }

    private Config createChunkedMapLoaderConfig(String mapName, int chunkSize, ChunkedLoader chunkedLoader) {
        Config cfg = new Config();
        cfg.setProperty(GroupProperties.PROP_PARTITION_COUNT, "1");
        cfg.setProperty(GroupProperties.PROP_MAP_LOAD_CHUNK_SIZE, String.valueOf(chunkSize));


        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setImplementation(chunkedLoader);

        MapConfig mapConfig = cfg.getMapConfig(mapName);
        mapConfig.setMapStoreConfig(mapStoreConfig);
        return cfg;
    }

    private static class ChunkedLoader extends SimpleMapLoader {

        private AtomicInteger numberOfChunks = new AtomicInteger(0);

        ChunkedLoader(int size, boolean slow) {
            super(size, slow);
        }

        @Override
        public Map loadAll(Collection keys) {
            numberOfChunks.incrementAndGet();
            return super.loadAll(keys);
        }
    }

    @Test(timeout = 120000)
    public void testIssue1142ExceptionWhenLoadAllReturnsNull() {
        Config config = new Config();
        String mapname = "testIssue1142ExceptionWhenLoadAllReturnsNull";
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setImplementation(new MapStoreAdapter<String, String>() {
            @Override
            public Set<String> loadAllKeys() {
                Set keys = new HashSet();
                keys.add("key");
                return keys;
            }

            public Map loadAll(Collection keys) {
                return null;
            }
        });
        config.getMapConfig(mapname).setMapStoreConfig(mapStoreConfig);
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance instance = nodeFactory.newHazelcastInstance(config);
        HazelcastInstance instance2 = nodeFactory.newHazelcastInstance(config);
        final IMap map = instance.getMap(mapname);
        for (int i = 0; i < 300; i++) {
            map.put(i, i);
        }
        assertEquals(300, map.size());
    }


    @Test(timeout = 120000)
    public void testReadingConfiguration() throws Exception {
        String mapName = "mapstore-test";
        InputStream is = getClass().getResourceAsStream("/com/hazelcast/config/hazelcast-mapstore-config.xml");
        XmlConfigBuilder builder = new XmlConfigBuilder(is);
        Config config = builder.build();
        HazelcastInstance hz = createHazelcastInstance(config);
        MapProxyImpl map = (MapProxyImpl) hz.getMap(mapName);
        MapService mapService = (MapService) map.getService();
        MapContainer mapContainer = mapService.getMapServiceContext().getMapContainer(mapName);
        MapStoreWrapper mapStoreWrapper = mapContainer.getMapStoreContext().getMapStoreWrapper();
        Iterator keys = mapStoreWrapper.loadAllKeys().iterator();

        final Set<String> loadedKeySet = loadedKeySet(keys);
        final Set<String> expectedKeySet = expectedKeySet();

        assertEquals(expectedKeySet, loadedKeySet);

        assertEquals("true", mapStoreWrapper.load("my-prop-1"));
        assertEquals("foo", mapStoreWrapper.load("my-prop-2"));
    }

    private Set<String> expectedKeySet() {
        final Set<String> expectedKeySet = new HashSet<String>();
        expectedKeySet.add("my-prop-1");
        expectedKeySet.add("my-prop-2");
        return expectedKeySet;
    }

    private Set<String> loadedKeySet(Iterator keys) {
        final Set<String> keySet = new HashSet<String>();
        while (keys != null && keys.hasNext()) {
            final String key = (String) keys.next();
            keySet.add(key);
        }
        return keySet;
    }

    @Test(timeout = 120000)
    public void testMapStoreNotCalledFromEntryProcessorBackup() throws Exception {
        final String mapName = "testMapStoreNotCalledFromEntryProcessorBackup_" + randomString();
        final int instanceCount = 2;
        Config config = new Config();
        // Configure map with one backup and dummy map store
        MapConfig mapConfig = config.getMapConfig(mapName);
        mapConfig.setBackupCount(1);
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        MapStoreWithStoreCount mapStore = new MapStoreWithStoreCount(1, 120);
        mapStoreConfig.setImplementation(mapStore);
        mapConfig.setMapStoreConfig(mapStoreConfig);

        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(instanceCount);
        HazelcastInstance instance1 = nodeFactory.newHazelcastInstance(config);
        HazelcastInstance instance2 = nodeFactory.newHazelcastInstance(config);

        final IMap<String, String> map = instance1.getMap(mapName);
        final String key = "key";
        final String value = "value";
        //executeOnKey
        map.executeOnKey(key, new ValueSetterEntryProcessor(value));
        mapStore.awaitStores();

        assertEquals(value, map.get(key));
        assertEquals(1, mapStore.getCount());
    }

    @Test(timeout = 120000)
    public void testMapStoreWriteRemoveOrder() {
        final String mapName = randomMapName("testMapStoreWriteDeleteOrder");
        final int numIterations = 10;
        final int writeDelaySeconds = 3;
        // create map store implementation
        final RecordingMapStore store = new RecordingMapStore(0, 1);
        // create hazelcast config
        final Config config = newConfig(mapName, store, writeDelaySeconds);
        // start hazelcast instance
        final HazelcastInstance hzInstance = createHazelcastInstance(config);
        // loop over num iterations
        final IMap<String, String> map = hzInstance.getMap(mapName);
        for (int k = 0; k < numIterations; k++) {
            String key = String.valueOf(k + 10); // 2 digits for sorting in output
            String value = "v:" + key;
            // add entry
            map.put(key, value);
            // sleep 300ms
            sleepMillis(1);
            // remove entry
            map.remove(key);
        }
        // wait for store to finish
        store.awaitStores();
        // wait for remove to finish
        store.awaitRemoves();

        assertEquals(0, store.getStore().keySet().size());
    }


    public static Config newConfig(Object storeImpl, int writeDelaySeconds) {
        return newConfig("default", storeImpl, writeDelaySeconds, InitialLoadMode.LAZY);
    }

    public static Config newConfig(Object storeImpl, int writeDelaySeconds, InitialLoadMode loadMode) {
        return newConfig("default", storeImpl, writeDelaySeconds, loadMode);
    }

    public static Config newConfig(String mapName, Object storeImpl, int writeDelaySeconds) {
        return newConfig(mapName, storeImpl, writeDelaySeconds, InitialLoadMode.LAZY);
    }

    public static Config newConfig(String mapName, Object storeImpl, int writeDelaySeconds, InitialLoadMode loadMode) {
        XmlConfigBuilder configBuilder = new XmlConfigBuilder();
        Config config = configBuilder.build();

        config.getManagementCenterConfig().setEnabled(false);
        MapConfig mapConfig = config.getMapConfig(mapName);
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setImplementation(storeImpl);
        mapStoreConfig.setWriteDelaySeconds(writeDelaySeconds);
        mapStoreConfig.setInitialLoadMode(loadMode);
        mapConfig.setMapStoreConfig(mapStoreConfig);
        return config;
    }

    public static class WaitingOnFirstTestMapStore extends TestMapStore {
        private AtomicInteger count;

        public WaitingOnFirstTestMapStore() {
            this.count = new AtomicInteger(0);
        }

        @Override
        public void storeAll(Map map) {
            if (count.get() == 0) {
                count.incrementAndGet();
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            super.storeAll(map);
        }
    }

    public static class TestMapStore extends MapStoreAdapter implements MapLoaderLifecycleSupport, MapStore {

        final Map store = new ConcurrentHashMap();

        final CountDownLatch latchStore;
        final CountDownLatch latchStoreAll;
        final CountDownLatch latchDelete;
        final CountDownLatch latchDeleteAll;
        final CountDownLatch latchLoad;
        final CountDownLatch latchLoadAllKeys;
        final CountDownLatch latchLoadAll;
        CountDownLatch latchStoreOpCount;
        CountDownLatch latchStoreAllOpCount;
        final AtomicInteger callCount = new AtomicInteger();
        final AtomicInteger initCount = new AtomicInteger();
        final AtomicInteger destroyCount = new AtomicInteger();
        private HazelcastInstance hazelcastInstance;
        private Properties properties;
        private String mapName;
        private boolean loadAllKeys = true;

        public TestMapStore() {
            this(0, 0, 0, 0, 0, 0);
        }

        public TestMapStore(int expectedStore, int expectedDelete, int expectedLoad) {
            this(expectedStore, 0, expectedDelete, 0, expectedLoad, 0);
        }

        public TestMapStore(int expectedStore, int expectedStoreAll, int expectedDelete,
                            int expectedDeleteAll, int expectedLoad, int expectedLoadAll) {
            this(expectedStore, expectedStoreAll, expectedDelete, expectedDeleteAll,
                    expectedLoad, expectedLoadAll, 0);
        }

        public TestMapStore(int expectedStore, int expectedStoreAll, int expectedDelete,
                            int expectedDeleteAll, int expectedLoad, int expectedLoadAll,
                            int expectedLoadAllKeys) {
            latchStore = new CountDownLatch(expectedStore);
            latchStoreAll = new CountDownLatch(expectedStoreAll);
            latchDelete = new CountDownLatch(expectedDelete);
            latchDeleteAll = new CountDownLatch(expectedDeleteAll);
            latchLoad = new CountDownLatch(expectedLoad);
            latchLoadAll = new CountDownLatch(expectedLoadAll);
            latchLoadAllKeys = new CountDownLatch(expectedLoadAllKeys);
        }

        public void init(HazelcastInstance hazelcastInstance, Properties properties, String mapName) {
            this.hazelcastInstance = hazelcastInstance;
            this.properties = properties;
            this.mapName = mapName;
            initCount.incrementAndGet();
        }

        public boolean isLoadAllKeys() {
            return loadAllKeys;
        }

        public void setLoadAllKeys(boolean loadAllKeys) {
            this.loadAllKeys = loadAllKeys;
        }

        public void destroy() {
            destroyCount.incrementAndGet();
        }

        public int getInitCount() {
            return initCount.get();
        }

        public int getDestroyCount() {
            return destroyCount.get();
        }

        public HazelcastInstance getHazelcastInstance() {
            return hazelcastInstance;
        }

        public String getMapName() {
            return mapName;
        }

        public Properties getProperties() {
            return properties;
        }

        public void assertAwait(int seconds) throws InterruptedException {
            assertTrue("Store remaining: " + latchStore.getCount(), latchStore.await(seconds, TimeUnit.SECONDS));
            assertTrue("Store-all remaining: " + latchStoreAll.getCount(), latchStoreAll.await(seconds, TimeUnit.SECONDS));
            assertTrue("Delete remaining: " + latchDelete.getCount(), latchDelete.await(seconds, TimeUnit.SECONDS));
            assertTrue("Delete-all remaining: " + latchDeleteAll.getCount(), latchDeleteAll.await(seconds, TimeUnit.SECONDS));
            assertTrue("Load remaining: " + latchLoad.getCount(), latchLoad.await(seconds, TimeUnit.SECONDS));
            assertTrue("Load-al remaining: " + latchLoadAll.getCount(), latchLoadAll.await(seconds, TimeUnit.SECONDS));
        }

        public Map getStore() {
            return store;
        }

        public void insert(Object key, Object value) {
            store.put(key, value);
        }

        public void store(Object key, Object value) {
            store.put(key, value);
            callCount.incrementAndGet();
            latchStore.countDown();
            if (latchStoreOpCount != null) {
                latchStoreOpCount.countDown();
            }
        }

        public Set loadAllKeys() {
            callCount.incrementAndGet();
            latchLoadAllKeys.countDown();
            if (!loadAllKeys) return null;
            return store.keySet();
        }

        public Object load(Object key) {
            callCount.incrementAndGet();
            latchLoad.countDown();
            return store.get(key);
        }

        public void storeAll(Map map) {
            store.putAll(map);
            callCount.incrementAndGet();
            latchStoreAll.countDown();

            if (latchStoreAllOpCount != null) {
                for (int i = 0; i < map.size(); i++) {
                    latchStoreAllOpCount.countDown();
                }
            }
        }

        public void delete(Object key) {
            store.remove(key);
            callCount.incrementAndGet();
            latchDelete.countDown();
        }

        public Map loadAll(Collection keys) {
            Map map = new HashMap(keys.size());
            for (Object key : keys) {
                Object value = store.get(key);
                if (value != null) {
                    map.put(key, value);
                }
            }
            callCount.incrementAndGet();
            latchLoadAll.countDown();
            return map;
        }

        public void deleteAll(Collection keys) {
            for (Object key : keys) {
                store.remove(key);
            }
            callCount.incrementAndGet();
            latchDeleteAll.countDown();
        }

    }

    public static class SimpleMapStore<K, V> extends MapStoreAdapter<K, V> {
        public final Map<K, V> store;
        private boolean loadAllKeys = true;

        public SimpleMapStore() {
            store = new ConcurrentHashMap<K, V>();
        }

        public SimpleMapStore(final Map<K, V> store) {
            this.store = store;
        }

        @Override
        public void delete(final K key) {
            store.remove(key);
        }

        @Override
        public V load(final K key) {
            return store.get(key);
        }

        @Override
        public void store(final K key, final V value) {
            store.put(key, value);
        }

        public Set<K> loadAllKeys() {
            if (loadAllKeys) {
                return store.keySet();
            }
            return null;
        }

        public void setLoadAllKeys(boolean loadAllKeys) {
            this.loadAllKeys = loadAllKeys;
        }

        @Override
        public void storeAll(final Map<K, V> kvMap) {
            store.putAll(kvMap);
        }
    }

    public static class TestEventBasedMapStore<K, V> implements MapLoaderLifecycleSupport, MapStore<K, V> {

        public enum STORE_EVENTS {
            STORE, STORE_ALL, DELETE, DELETE_ALL, LOAD, LOAD_ALL, LOAD_ALL_KEYS
        }

        protected final Map<K, V> store = new ConcurrentHashMap();


        protected final BlockingQueue events = new LinkedBlockingQueue();
        protected final AtomicInteger storeCount = new AtomicInteger();
        protected final AtomicInteger storeAllCount = new AtomicInteger();
        protected final AtomicInteger loadCount = new AtomicInteger();
        protected final AtomicInteger callCount = new AtomicInteger();
        protected final AtomicInteger initCount = new AtomicInteger();
        protected HazelcastInstance hazelcastInstance;
        protected Properties properties;
        protected String mapName;
        protected boolean loadAllKeys = true;
        protected CountDownLatch storeLatch;
        protected CountDownLatch deleteLatch;
        protected CountDownLatch loadAllLatch;

        public void init(HazelcastInstance hazelcastInstance, Properties properties, String mapName) {
            this.hazelcastInstance = hazelcastInstance;
            this.properties = properties;
            this.mapName = mapName;
            initCount.incrementAndGet();
        }

        public BlockingQueue getEvents() {
            return events;
        }

        public void destroy() {
        }

        public int getEventCount() {
            return events.size();

        }

        public int getInitCount() {
            return initCount.get();
        }

        public boolean isLoadAllKeys() {
            return loadAllKeys;
        }

        public void setLoadAllKeys(boolean loadAllKeys) {
            this.loadAllKeys = loadAllKeys;
        }

        public HazelcastInstance getHazelcastInstance() {
            return hazelcastInstance;
        }

        public String getMapName() {
            return mapName;
        }

        public Properties getProperties() {
            return properties;
        }

        Map getStore() {
            return store;
        }

        public TestEventBasedMapStore insert(K key, V value) {
            store.put(key, value);
            return this;
        }

        public void store(K key, V value) {
            store.put(key, value);
            callCount.incrementAndGet();
            storeCount.incrementAndGet();
            if (storeLatch != null) {
                storeLatch.countDown();
            }
            events.offer(STORE_EVENTS.STORE);
        }

        public V load(K key) {
            callCount.incrementAndGet();
            loadCount.incrementAndGet();
            events.offer(STORE_EVENTS.LOAD);
            return store.get(key);
        }

        public void storeAll(Map map) {
            store.putAll(map);
            callCount.incrementAndGet();
            final int size = map.size();
            if (storeLatch != null) {
                for (int i = 0; i < size; i++) {
                    storeLatch.countDown();
                }
            }
            events.offer(STORE_EVENTS.STORE_ALL);
        }

        public void delete(K key) {
            store.remove(key);
            callCount.incrementAndGet();
            if (deleteLatch != null) {
                deleteLatch.countDown();
            }
            events.offer(STORE_EVENTS.DELETE);
        }

        public Set<K> loadAllKeys() {
            if (loadAllLatch != null) {
                loadAllLatch.countDown();
            }
            callCount.incrementAndGet();
            events.offer(STORE_EVENTS.LOAD_ALL_KEYS);
            if (!loadAllKeys) return null;
            return store.keySet();
        }

        public Map loadAll(Collection keys) {
            Map map = new HashMap(keys.size());
            for (Object key : keys) {
                Object value = store.get(key);
                if (value != null) {
                    map.put(key, value);
                }
            }
            callCount.incrementAndGet();
            events.offer(STORE_EVENTS.LOAD_ALL);
            return map;
        }

        public void deleteAll(Collection keys) {
            for (Object key : keys) {
                store.remove(key);
            }
            callCount.incrementAndGet();

            if (deleteLatch != null) {
                for (int i = 0; i < keys.size(); i++) {
                    deleteLatch.countDown();
                }
            }
            events.offer(STORE_EVENTS.DELETE_ALL);
        }
    }

    private static class ValueSetterEntryProcessor extends AbstractEntryProcessor<String, String> {
        private final String value;

        ValueSetterEntryProcessor(String value) {
            this.value = value;
        }

        public Object process(Map.Entry entry) {
            entry.setValue(value);
            return null;
        }
    }

    static class NoDuplicateMapStore extends TestMapStore {
        boolean failed = false;

        @Override
        public void store(Object key, Object value) {
            if (store.containsKey(key)) {
                failed = true;
                throw new RuntimeException("duplicate is not allowed");
            }
            super.store(key, value);
        }

        @Override
        public void storeAll(Map map) {
            for (Object key : map.keySet()) {
                if (store.containsKey(key)) {
                    failed = true;
                    throw new RuntimeException("duplicate is not allowed");
                }
            }
            super.storeAll(map);
        }
    }

    static class ProcessingStore extends MapStoreAdapter<Integer, Employee> implements PostProcessingMapStore {
        @Override
        public void store(Integer key, Employee employee) {
            employee.setSalary(employee.getAge() * 1000);
        }
    }

    public static class MapStoreWithStoreCount extends SimpleMapStore {
        final CountDownLatch latch;
        final int waitSecond;
        final AtomicInteger count = new AtomicInteger(0);
        final int sleepStoreAllSeconds;

        public MapStoreWithStoreCount(int expectedStore, int seconds) {
            latch = new CountDownLatch(expectedStore);
            waitSecond = seconds;
            sleepStoreAllSeconds = 0;
        }

        public MapStoreWithStoreCount(int expectedStore, int seconds, int sleepStoreAllSeconds) {
            latch = new CountDownLatch(expectedStore);
            waitSecond = seconds;
            this.sleepStoreAllSeconds = sleepStoreAllSeconds;
        }

        public void awaitStores() {
            assertOpenEventually(latch, waitSecond);
        }

        @Override
        public void store(Object key, Object value) {
            latch.countDown();
            super.store(key, value);
            count.incrementAndGet();
        }

        @Override
        public void storeAll(Map map) {
            if (sleepStoreAllSeconds > 0) {
                try {
                    Thread.sleep(sleepStoreAllSeconds * 1000);
                } catch (InterruptedException e) {
                }
            }
            for (Object o : map.keySet()) {
                latch.countDown();
                count.incrementAndGet();
            }
            super.storeAll(map);
        }

        public int getCount() {
            return count.get();
        }
    }

    public static class BasicMapStoreFactory implements MapStoreFactory<String, String> {

        @Override
        public MapLoader<String, String> newMapStore(String mapName, final Properties properties) {
            return new MapStore<String, String>() {
                @Override
                public void store(String key, String value) {
                }

                @Override
                public void storeAll(Map map) {
                }

                @Override
                public void delete(String key) {
                }

                @Override
                public void deleteAll(Collection keys) {
                }

                @Override
                public String load(String key) {
                    return properties.getProperty(key.toString());
                }

                @Override
                public Map<String, String> loadAll(Collection<String> keys) {
                    Map<String, String> map = new HashMap<String, String>();
                    for (String key : keys) {
                        map.put(key, properties.getProperty(key));
                    }
                    return map;
                }

                @Override
                public Set<String> loadAllKeys() {
                    return new HashSet<String>(properties.stringPropertyNames());
                }
            };
        }
    }
}
