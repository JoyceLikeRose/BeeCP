/*
 * Copyright Chris2018998
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
package cn.beecp.util;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.LockSupport;

/**
 * TransferQueue Implementation with class <tt>ConcurrentLinkedQueue</tt>
 * 
 * @author Chris.Liao
 */
public class ConcurrentTransferQueue<E> extends AbstractQueue<E> {
	private static final class Waiter {
		//poll thread
		Thread thread = Thread.currentThread();
		//transfer value or waiter status
		volatile Object stateValue = STS_NORMAL;
	}
	private static final class Status {}

	/**
	 * Waiter normal status
	 */
	private static final Status STS_NORMAL = new Status();

	/**
	 * Waiter in waiting status
	 */
	private static final Status STS_WAITING = new Status();

	/**
	 * Waiter thread interrupted
	 */
	private static final Status STS_INTERRUPTED = new Status();

	/**
	 * CAS updater on waiter's transferValue field
	 */
	private static final AtomicReferenceFieldUpdater<Waiter, Object> TransferUpdater = AtomicReferenceFieldUpdater
			.newUpdater(Waiter.class, Object.class, "stateValue");
	/**
	 * store element
	 */
	private ConcurrentLinkedQueue<E> elementQueue = new ConcurrentLinkedQueue<E>();

	/**
	 * store poll waiter
	 */
	private ConcurrentLinkedQueue<Waiter> waiterQueue = new ConcurrentLinkedQueue<Waiter>();

	/**
	 * Retrieves, but does not remove, the head of this queue,
	 * or returns {@code null} if this queue is empty.
	 *
	 * @return the head of this queue, or {@code null} if this queue is empty
	 */
	public E peek(){
		return elementQueue.peek();
	}

	/**
	 * Returns the number of elements in this queue.  If this queue
	 * contains more than {@code Integer.MAX_VALUE} elements, returns
	 * {@code Integer.MAX_VALUE}.
	 *
	 * <p>Beware that, unlike in most collections, this method is
	 * <em>NOT</em> a constant-time operation. Because of the
	 * asynchronous nature of these queues, determining the current
	 * number of elements requires an O(n) traversal.
	 * Additionally, if elements are added or removed during execution
	 * of this method, the returned result may be inaccurate.  Thus,
	 * this method is typically not very useful in concurrent
	 * applications.
	 *
	 * @return the number of elements in this queue
	 */
	public  int size(){
		return elementQueue.size();
	}

	/**
	 * Returns an iterator over the elements in this queue in proper sequence.
	 * The elements will be returned in order from first (head) to last (tail).
	 *
	 * <p>The returned iterator is
	 * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
	 *
	 * @return an iterator over the elements in this queue in proper sequence
	 */
	public Iterator<E> iterator(){
		return elementQueue.iterator();
	}

	/**
	 * add element to queue,if exists poll waiter,then transfer it to waiter directly,
	 * if not exists,then add it to element queue;
	 *
	 * 	@param e element expect to add into queue
	 *  @return  boolean ,true:successful to transfer or add into queue
	 */
	public boolean offer(E e) {
		Iterator<Waiter> iterator = waiterQueue.iterator();
		while (iterator.hasNext()) {
			Waiter waiter = iterator.next();
			for (Object state = waiter.stateValue;(state==STS_NORMAL||state==STS_WAITING);state=waiter.stateValue) {
				if (TransferUpdater.compareAndSet(waiter, state, e)) {
					if (state == STS_WAITING)LockSupport.unpark(waiter.thread);
					return true;
				}
			}
		}

		return elementQueue.offer(e);
	}

	/**
	 * Poll one element from queue,if not exists,then wait one transferred
	 *
	 *  @return element
	 */
	public E poll() {
		try {
			return poll(-1, TimeUnit.NANOSECONDS);
		}catch(InterruptedException e){
			return null;
		}
	}

	/**
	 * Retrieves and removes the head of this queue, waiting up to the
	 * specified wait time if necessary for an element to become available.
	 *
	 * @param timeout how long to wait before giving up, in units of
	 *        {@code unit}
	 * @param unit a {@code TimeUnit} determining how to interpret the
	 *        {@code timeout} parameter
	 * @return the head of this queue, or {@code null} if the
	 *         specified waiting time elapses before an element is available
	 * @throws InterruptedException if interrupted while waiting
	 */
	public E poll(long timeout, TimeUnit unit) throws InterruptedException {
		E e = elementQueue.poll();
		if(e != null)return e;

		Waiter waiter = new Waiter();
		try {
			waiterQueue.offer(waiter);
			if (waiter.stateValue == STS_NORMAL && TransferUpdater.compareAndSet(waiter, STS_NORMAL, STS_WAITING)) {
				if (timeout > 0)
					LockSupport.parkNanos(unit.toNanos(timeout));
				else
					LockSupport.park(waiter);

				if (waiter.stateValue == STS_WAITING && waiter.thread.isInterrupted() && TransferUpdater.compareAndSet(waiter, STS_WAITING, STS_INTERRUPTED)) {
					throw new InterruptedException();
				}
			}
		}finally {
			waiterQueue.remove(waiter);
		}

		if(waiter.stateValue instanceof Status) {
			return null;
		}else {
			return (E)waiter.stateValue;
		}
	}
}