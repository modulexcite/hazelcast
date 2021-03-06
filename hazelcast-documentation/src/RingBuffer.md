## Ringbuffer

The Ringbuffer is a data structure where the data is stored in a ring-like structure. You can think of it as a circular array with a 
certain capacity. Each Ringbuffer has a tail and a head. The tail is where the items are added and the head is where items are overwritten 
or expired. You can reach each element in a Ringbuffer using a sequence ID, which is mapped to the elements between the head 
and tail (inclusive) of the Ringbuffer. 

Reading from the Ringbuffer is very simple. Just get the current head and start reading. The 'readOne' returns the item at the 
given sequence or blocks if no item is available. To read the next item, the sequence is incremented by one.
```java
Ringbuffer<String> ringbuffer = hz.getRingbuffer("rb");
long sequence = ringbuffer.headSequence();
while(true){
    String item = ringbuffer.readOne(sequence);
    sequence++;
    ... process item
}  
```
By exposing the sequence you can now move any item from the ringbuffer as long as the item is still available. If it isn't available
anymore, a StaleSequenceException is thrown.

Adding an item to the ringbuffer is also very easy:
```java
Ringbuffer<String> ringbuffer = hz.getRingbuffer("rb");
ringbuffer.add("someitem")
```

The add method returns the sequence of the inserted item and this value will always be unique. This can sometimes be used for a 
very cheap way of generating unique id's if you are already making use of the Ringbuffer.

### IQueue vs Ringbuffer.
The Ringbuffer can sometimes be a better alternative than an IQueue. Unlike IQueue, the Ringbuffer does not remove the items, it only
reads the items using a certain position. There are many advantages using this approach:
* The same item can be read multiple times by the same thread; this is useful for realizing semantics of read-at-least-once or 
read-at-most-once.
* The same item can be read by multiple threads. Normally you could use a IQueue per thread for the same semantic, but this is a 
lot less efficient because of increased remoting. A take from an IQueue is destructive, so the change needs to be replicated.
* Reads are extremely cheap since there is no change in the Ringbuffer, therefore no replication is required. 
* Reads and writes can be batched to speed up performance. Using read (and write) batching can dramatically improve performance 
of the Ringbuffer.

### Capacity
By default configured A Ringbuffer is with a capacity of 10000 items. Internally an array is created with exactly that size. If 
the a time-to-live is configured, then also an array of longs to store the expiration time for every item. In a lot of cases you
want to change this number to something that fits your needs better. 

Below is a code example of a ringbuffer with a capacity of 2000 items.
```xml
<ringbuffer name="rb">
    <capacity>2000</capacity>
</ringbuffer>
```
For now, the Ringbuffer is not a partitioned data structure; its data is stored in a single partition and the replicas are 
stored in another partition. So don't create larger ringbuffer than that safely can fit in a single JVM. 

### Synchronous and Asynchronous backups.
A Ringbuffer by default has a single synchronous backup. But this can be controlled, just like most of the other Hazelcast 
distributed data-structures by setting the sync and async backups. In the example below a ringbuffer is configured with 0
sync backups and 1 async backup:

```xml
<ringbuffer name="rb">
    <backup-count>0</backup-count>
    <async-backup-count>1</async-backup-count>
</ringbuffer>
```
An async backup will probably give you better performance, but there is a chance that the chance that the item added is lost 
when the member owning the primary crashed before the replication has completed. Probably you want to have a look at the batching
methods if you need high performance but don't want to give up on consistency.

### Time to live.
The Ringbuffer can be configured with a time to live in seconds. Using this setting, you can control how long the items remain in 
the Ringbuffer before they are expired. By default, the time to live is set to 0, meaning that unless the item is overwritten, 
it will remain in the Ringbuffer indefinitely. If a time to live is set and an item is added, then depending on the OverwritePolicy, 
either the oldest item is overwritten, or the call is rejected. 

In the code fragment below a ringbuffer is configured with a time to live of 180 seconds.
```xml
<ringbuffer name="rb">
    <time-to-live-seconds>180</time-to-live-seconds>
</ringbuffer>
```

### Overflow policy
using the overflow policy one can determine what to do if the oldest item in the ringbuffer is not old enough to expire. There
are currently 2 options available:
* OverflowPolicy.OVERWRITE: in this case the oldest item is overwritten. You might wonder why not set the time to live to 0, since
that also doesn't prevent and overwrite to take place. The difference is that id a time-to-live is set, the items still get expired
if there is enough capacity in the ringbuffer.
* OverflowPolicy.FAIL: the call is aborted. The methods that make use of the OverflowPolicy return -1 to indicate that adding
the item has failed. 

Using the the overflow policy gives fine control on what to do if the Ringbuffer is full. The policy can also be used to making 
a back pressure mechanism. Below a code example can be found where an exponential backoff is used.

```java
long sleepMs = 100;
for (; ; ) {
    long result = ringbuffer.addAsync(item, OverflowPolicy.FAIL).get();
    if (result != -1) {
        break;
    }
    
    TimeUnit.MILLISECONDS.sleep(sleepMs);
    sleepMs = min(5000, sleepMs * 2);
}
```

### In Memory Format
The Ringbuffer can also be configured with an InMemoryFormat which controls the format of stored items. By default, `BINARY` is used, 
meaning that the object is stored in a serialized form. You can select the `OBJECT` InMemoryFormat, which is useful when filtering is 
applied or when the `OBJECT` InMemoryFormat can lead to a smaller memory footprint than a `BINARY`. 

In the example below a ringbuffer is configured with a OBJECT In Memory Format:

```xml
<ringbuffer name="rb">
    <in-memory-format>BINARY</in-memory-format>
</ringbuffer>
```

### addAllAsync

In the previous examples the Ringbuffer.add method was being used to add an item to the ringbuffer. The problem with the 
`Ringbuffer::add` is that it always overwrites and that it doesn't support batching. Batching can have a huge
impact on performance. That is why the `addAllAsync` method was added. 

Example:

```java
List<String> items = Arrays.asList("1","2","3")
ICompletableFuture<Long> f = rb.addAllAsync(items, OverflowPolicy.OVERWRITE);
f.get()
```        
In this case the 3 strings are being added to the ringbuffer using the OverflowPolicy.OVERWRITE policy. Check the OverflowPolicy
for more details.

### readManyAsync

In the previous example the `readOne` was being used. It is simple, but it isn't very efficient:
* doesn't make use of batching
* can't filter items at the source; they need to be retrieved before being filtered.

That is why the readManyAsync method was added:

```java
ICompletableFuture<ReadResultSet<E>> readManyAsync(
   long startSequence, 
   int minCount,                                              
   int maxCount, 
   IFunction<E, Boolean> filter);
```
This call can read a batch of items and can filter items at the source. The meaning of the arguments:
* startSequence: the sequence of the first item to read
* minCount: the minimum number of items to read. If you don't want to block, provide 0. If you do want to block for at least one item,
provide 1.
* the maximum number of items to retrieve. There is a hard cap on the maxCount and that is 1000.
* filter: a function that accept an item and checks if it should be returned. If no filtering should be applied, pass null.

A full example:
```java
long sequence = rb.headSequence();
for(;;) {
    ICompletableFuture<ReadResultSet<String>> f = rb.readManyAsync(sequence, 1, 10, null);
    ReadResultSet<String> rs = f.get();
    for (String s : rs) {
        System.out.println(s);
    }
    sequence+=rs.readCount();
}
```        
Please take a careful look at how the sequence is being incremented. You can't always rely on the number of items being returned
if items are filtered out.

### Async methods
The Ringbuffer provides asynchronous methods for the more powerful methods like batched reading with filtering or batch writing. 
To make these methods synchronous, just call `get()` on the returned future.

Example:
```java
ICompletableFuture f = ringbuffer.addAsync(item, OverflowPolicy.FAIL);
f.get();
```

But the ICompletableFuture can also be used to get notified when the operation has completed. For example when you want to 
get notified when a batch of reads has completed:
```java
ICompletableFuture<ReadResultSet<String>> f = rb.readManyAsync(sequence, min, max, someFilter);
f.andThen(new ExecutionCallback<ReadResultSet<String>>() {
   @Override
   public void onResponse(ReadResultSet<String> response) {
        for (String s : response) {
            System.out.println("Received:" + s);
        }
   }

   @Override
   public void onFailure(Throwable t) {
        t.printStackTrace();
   }
});
```
THe advantage of this approach is that the thread that does the call isn't block till the response is returned.

### Full Configuration examples

The following snippet shows the XML configuration of a ringbuffer called 'rb'. The configuration is modeled after the ringbuffers defaults.

```xml
<ringbuffer name="rb">
    <capacity>10000</capacity>
    <backup-count>1</backup-count>
    <async-backup-count>0</async-backup-count>
    <time-to-live-seconds>0</time-to-live-seconds>
    <in-memory-format>BINARY</in-memory-format>
</ringbuffer>
```

A Ringbuffer can also be configured using our programmatic API. Below a full example can be seen of the above XML version:

```java
RingbufferConfig rbConfig = new RingbufferConfig("rb")
    .setCapacity(10000)
    .setBackupCount(1)
    .setAsyncBackupCount(0)
    .setTimeToLiveSeconds(0)
    .setInMemoryFormat(InMemoryFormat.BINARY);
Config config = new Config();
config.addRingbufferConfig(rbConfig);
```        

***RELATED INFORMATION***

*Please refer to the [Ringbuffer Configuration section](#ringbuffer-configuration) for more information on configuring the Ringbuffer.*

