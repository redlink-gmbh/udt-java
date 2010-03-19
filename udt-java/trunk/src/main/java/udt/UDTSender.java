/*********************************************************************************
 * Copyright (c) 2010 Forschungszentrum Juelich GmbH 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * (1) Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the disclaimer at the end. Redistributions in
 * binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution.
 * 
 * (2) Neither the name of Forschungszentrum Juelich GmbH nor the names of its 
 * contributors may be used to endorse or promote products derived from this 
 * software without specific prior written permission.
 * 
 * DISCLAIMER
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *********************************************************************************/

package udt;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import udt.packets.Acknowledgement;
import udt.packets.Acknowledgment2;
import udt.packets.DataPacket;
import udt.packets.KeepAlive;
import udt.packets.NegativeAcknowledgement;
import udt.sender.SenderLossList;
import udt.sender.SenderLossListEntry;
import udt.util.UDTThreadFactory;


/**
 * sender part of a UDT entity
 * 
 * @see UDTReceiver
 * 
 * 
 */
public class UDTSender {

	private static final Logger logger=Logger.getLogger(UDTClient.class.getName());

	private final UDPEndPoint endpoint;

	private final UDTSession session;
	//sendLossList store the sequence numbers of lost packets
	//feed back by the receiver through NAK pakets
	private final SenderLossList senderLossList;
	//sendBuffer stores the sent data packets and their sequence numbers
	private final Map<Long,DataPacket>sendBuffer;
	//sendQueue contains the packets to send
	private final BlockingQueue<DataPacket>sendQueue;
	//thread reading packets from send queue and sending them
	private Thread senderThread;
	//time to live for a packet in the loss list(TTL)
	private long timeToLive;

	//protects against races when reading/writing to the sendBuffer
	private final Object sendLock=new Object();

	//number of unacknowledged data packets
	private final AtomicInteger unacknowledged=new AtomicInteger(0);

	//for generating data packet sequence numbers
	private long nextSequenceNumber=-1;

	//the largest data packet sequence number that has actually been sent out
	private volatile long largestSentSequenceNumber=-1;

	//last acknowledge number, initialised to the initial sequence number
	private long lastAckSequenceNumber=0;

	//size of the send queue
	public static final int MAX_SIZE=1024;

	private volatile boolean stopped=false;

	public UDTSender(UDTSession session,UDPEndPoint endpoint){
		this.endpoint= endpoint;
		this.session=session;
		if(!session.isReady())throw new IllegalStateException("UDTSession is not ready.");
		senderLossList=new SenderLossList();
		sendBuffer=new ConcurrentHashMap<Long, DataPacket>(64,0.75f,2); 
		sendQueue = new LinkedBlockingQueue<DataPacket>(MAX_SIZE);  
		start();
	}

	//starts the sender algorithm
	private void start(){
		Runnable r=new Runnable(){
			public void run(){
				try{
					while(!stopped){
						senderAlgorithm();
					}
				}catch(InterruptedException ie){
					ie.printStackTrace();
				}
				catch(IOException ex){
					logger.log(Level.SEVERE,"",ex);
				}
				logger.info("STOPPING SENDER for "+session);
			}
		};
		senderThread=UDTThreadFactory.get().newThread(r);
		senderThread.start();
	}


	/** 
	 * sends the given data packet, storing the relevant information
	 * 
	 * @param data
	 * @throws IOException
	 * @throws InterruptedException
	 */ 
	private void send(DataPacket p)throws IOException{
		synchronized(sendLock){
			endpoint.doSend(p);
			sendBuffer.put(p.getPacketSequenceNumber(), p);
			unacknowledged.incrementAndGet();
		}
		session.getStatistics().incNumberOfSentDataPackets();
	}

	/**
	 * writes a data packet into the sendQueue
	 * @return <code>true</code>if the packet was added, <code>false</code> if the
	 * packet could not be added because the queue was full
	 */
	protected boolean sendUdtPacket(DataPacket p)throws IOException{
		return sendQueue.offer(p);
	}

	/**
	 * writes a data packet into the sendQueue, waiting at most for the specified time
	 * if this is not possible due to a full send queue
	 * 
	 * @return <code>true</code>if the packet was added, <code>false</code> if the
	 * packet could not be added because the queue was full
	 * @param p
	 * @param timeout
	 * @param units
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	protected boolean sendUdtPacket(DataPacket p, int timeout, TimeUnit units)throws IOException,InterruptedException{
		return sendQueue.offer(p,timeout,units);
	}

	//receive a packet from server from the peer
	protected void receive(UDTPacket p)throws IOException{
		if (p instanceof Acknowledgement) {
			Acknowledgement acknowledgement=(Acknowledgement)p;
			onAcknowledge(acknowledgement.getAckNumber());
			return;
		}

		else if (p instanceof NegativeAcknowledgement) {
			NegativeAcknowledgement nak=(NegativeAcknowledgement)p;
			onNAKPacketReceived(nak);
		}
	}

	protected void onAcknowledge(long ackNumber)throws IOException{
		//need to remove all sequence numbers up the ack number from the sendBuffer
		boolean removed=false;
		for(long s=lastAckSequenceNumber;s<ackNumber;s++){
			synchronized (sendLock) {
				removed=sendBuffer.remove(s)!=null;
			}
			if(removed){
				unacknowledged.decrementAndGet();
			}
		}
		lastAckSequenceNumber=ackNumber;
		//send ACK2 packet to the receiver
		sendAck2(ackNumber);
	}

	/**
	 * procedure when a NAK is received (spec. p 14)
	 * @param nak
	 */
	protected void onNAKPacketReceived(NegativeAcknowledgement nak){
		for(Integer i: nak.getDecodedLossInfo()){
			senderLossList.insert(new SenderLossListEntry(i));
		}
		//update SND TODO

		//reset EXP. EXP is in the receiver currently.... maybe move to SOCKET?
		session.getSocket().getReceiver().resetEXPTimer();
		session.getStatistics().incNumberOfNAKReceived();
		return;
	}

	//send single keep alive packet -> move to socket!
	protected void sendKeepAlive()throws Exception{
		KeepAlive keepAlive = new KeepAlive();
		//TODO
		keepAlive.setDestinationID(0L);
		endpoint.doSend(keepAlive);
	}

	protected void sendAck2(long ackSequenceNumber)throws IOException{
		Acknowledgment2 ackOfAckPkt = new Acknowledgment2();
		ackOfAckPkt.setDestinationID(0L);
		ackOfAckPkt.setAckSequenceNumber(ackSequenceNumber);
		endpoint.doSend(ackOfAckPkt);
	}

	/**
	 * sender algorithm
	 */
	public void senderAlgorithm()throws InterruptedException, IOException{
		//if the sender's loss list is not empty 
		SenderLossListEntry entry=senderLossList.getFirstEntry();
		if (entry!=null) {
			long seqNumber = entry.getSequenceNumber();
			//if the current seqNumber is 16n,check the timeOut in the 
			//loss list and send a message drop request.
			if((seqNumber%16)==0){
				//TODO
				//sendLossList.checkTimeOut(timeToLive);
			}
			try {
				//retransmit the packet with the first entry in the list
				//as sequence number and remove it from  the list
				DataPacket pktToRetransmit = sendBuffer.get(seqNumber);
				if(pktToRetransmit!=null){
					endpoint.doSend(pktToRetransmit);
					session.getStatistics().incNumberOfRetransmittedDataPackets();
				}
				senderLossList.remove(seqNumber);
			}catch (Exception e) {
				logger.log(Level.WARNING,"",e);
			}
		}

		else {
			//if the number of unacknowledged data packets exceeds the flow
			//window size, wait for an ACK
			int unAcknowledged=unacknowledged.get();
			if(unAcknowledged<session.getFlowWindowSize()){
				DataPacket dp=sendQueue.poll(10,TimeUnit.MILLISECONDS);
				if(dp!=null){
					send(dp);
					largestSentSequenceNumber=dp.getPacketSequenceNumber();
				}
			}else{
				//should we *really* wait for an ack?!
			}
		}
		Thread.yield();
	}

	/**
	 * for processing EXP event (see spec. p 13)
	 */
	protected void putUnacknowledgedPacketsIntoLossList(){
		synchronized (sendLock) {
			for(Long l: sendBuffer.keySet()){
				senderLossList.insert(new SenderLossListEntry(l));
				logger.fine("NO ACK FOR "+l);
			}
		}
	}

	/**
	 * the next sequence number for data packets.
	 * The initial sequence number is "0"
	 */
	public long getNextSequenceNumber(){
		nextSequenceNumber++;
		return nextSequenceNumber;
	}

	public long getCurrentSequenceNumber(){
		return nextSequenceNumber;
	}

	/**
	 * returns the largest sequence number sent so far
	 */
	public long getLargestSentSequenceNumber(){
		return largestSentSequenceNumber;
	}
	boolean haveAcknowledgementFor(long sequenceNumber){
		return !sendBuffer.containsKey(sequenceNumber);
	}

	boolean isSentOut(long sequenceNumber){
		return largestSentSequenceNumber>=sequenceNumber;

	}
	boolean haveLostPackets(){
		return senderLossList.isEmpty();
	}

	public void stop(){
		stopped=true;
	}
}
