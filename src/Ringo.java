import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Hashtable;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Iterator;
import java.lang.Thread;
import java.util.Scanner;

import java.io.IOException;

/**
 * The Ringo class represents a network node on the Ringo network.
 * It runs in a child thread of the main process, which belongs to App.java.
 * It's main functions are: providing a user interface, managing child threads
 * to coordinate network input and network output, and keeping connections with
 * it's peers alive. It is essentially the backbone of the Ringo protocol.
 * 
 * @author sainaidu
 * @author andrewray
 */
public class Ringo implements Runnable {
	DatagramSocket socket;
	private final Role role;
	private String localName;
	private final int localPort;
	private String pocName;
	private final int pocPort;
	private final int ringSize;

	private Hashtable<String, Integer> lsa;
	private Hashtable<String, Integer> rttIndex;
	private Hashtable<Integer, String> indexRtt;
	private long [][] rtt;
	private LinkedBlockingQueue<RingoPacket> recvQueue;
	private LinkedBlockingQueue<RingoPacket> sendQueue;
	private LinkedBlockingQueue<RingoPacket> keepAliveQueue;
	private RingTracker tracker;
	private RingoPacketFactory factory;
	private KeepAlive keepalive;
	private Thread keepAliveThread;

	/**
	 * The constructor accepts all of the command-line arguments specified in the
	 * reference material
	 */
	public Ringo(Role role, int localPort, String pocName, int pocPort, int ringSize, DatagramSocket socket) {		
		this.socket = socket;
		this.role = role;
		this.localName = "";
		try {
			this.localName = InetAddress.getLocalHost().getHostAddress();
		} catch (Exception e) {
			e.printStackTrace();
		}
		this.localPort = localPort;
		this.pocName = pocName;
		if (this.pocName != null) {
			try {
				if (this.pocName.equals("localhost")) {
					this.pocName = InetAddress.getLocalHost().getHostAddress();
				} else {
					this.pocName = InetAddress.getByName(pocName).getHostAddress();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		this.pocPort = pocPort;
		this.ringSize = ringSize;
		this.lsa = new Hashtable<String, Integer>();
		this.rttIndex = new Hashtable<String, Integer>();
		this.indexRtt = new Hashtable<Integer, String>();
		this.rtt = new long[ringSize][ringSize];
		for (int i = 0; i < this.rtt.length; i++) {
			for (int j = 0; j < this.rtt.length; j++) {
				this.rtt[i][j] = -1;
			}
		}
		this.recvQueue = new LinkedBlockingQueue<RingoPacket>();
		this.sendQueue = new LinkedBlockingQueue<RingoPacket>();
		this.keepAliveQueue = new LinkedBlockingQueue<RingoPacket>();
		this.factory = new RingoPacketFactory(localName, localPort, role, ringSize);
	}

	/**
	 * This function is the first step in thread execution.
	 * 
	 * Since this class is used as a Thread, when it is instantiated
	 * it immediately invokes this function.
	 * 
	 * This function acts as the initializer for this Ringo's network
	 * activities and contains the logic for the user interface.
	 * 
	 * It is the single-point-of-failure in this class and is thus
	 * the "most important" function overall.
	 */
	public void run() {
		LinkedBlockingQueue<RingoPacket> recvQueue = this.recvQueue;
		LinkedBlockingQueue<RingoPacket> sendQueue = this.sendQueue;
		LinkedBlockingQueue<RingoPacket> keepAliveQueue = this.keepAliveQueue;
		

		Thread netIn = new Thread(new ReceiverThread(recvQueue, keepAliveQueue));
		Thread netOut = new Thread(new SenderThread(sendQueue));
		netIn.start();
		netOut.start();
		
		if (this.pocName != "0" && this.pocPort != 0) {
			RingoPacket responseIn = null;
			RingoPacket packet = new RingoPacket(this.localName, this.localPort, this.pocName, this.pocPort, 0, 0, PacketType.PING_REQ, this.role, this.ringSize);
			sendQueue.add(packet);
			responseIn = this.takeType(recvQueue, PacketType.PING_RES);
			if (responseIn == null) {
				System.out.println("\nPoint of contact not reachable currently. Continuing to attempt connection...");
			}
			
			while(responseIn == null) {
				packet = new RingoPacket(this.localName, this.localPort, this.pocName, this.pocPort, 0, 0, PacketType.PING_REQ, this.role, this.ringSize);
				sendQueue.add(packet);
				responseIn = this.takeType(recvQueue, PacketType.PING_RES);
			}
		}
		
		recvQueue.clear();
		sendQueue.clear();
		
		System.out.println("\nStarting peer discovery...");
		peerDiscovery(recvQueue, sendQueue);
		try {
			Thread.sleep(500);
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println("Peer discovery complete!\n");
		flushType(recvQueue, PacketType.LSA);
		flushType(recvQueue, PacketType.LSA_COMPLETE);
		System.out.println("Starting RTT Vector creation...");
		rttVectorGeneration(recvQueue, sendQueue);
		try {
			Thread.sleep(3000);
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println("RTT Vector creation complete!\n");
		flushType(recvQueue, PacketType.LSA);
		flushType(recvQueue, PacketType.LSA_COMPLETE);
		flushType(recvQueue, PacketType.PING_RES);
		flushType(recvQueue, PacketType.PING_COMPLETE);
		try {
			Thread.sleep(3000);
		} catch (Exception e) {
			e.printStackTrace();
		}
		flushType(recvQueue, PacketType.PING_RES);
		flushType(recvQueue, PacketType.PING_COMPLETE);
		System.out.println("Starting RTT Matrix convergence...");
		rttConvergence(recvQueue, sendQueue);
		try {
			Thread.sleep(3000);
		} catch (Exception e) {
			e.printStackTrace();
		}

		System.out.println("RTT Matrix convergence complete!");
		System.out.println("Network is ready to use.\n");
		Scanner scanner = new Scanner(System.in);
		
		// (String me, long[][] rtt, Hashtable<Integer, String> indexRTT)
		
		tracker = new RingTracker(this.localName + ":" + this.localPort, rtt, indexRtt);
		keepalive = new KeepAlive(keepAliveQueue, sendQueue, factory, tracker);
		keepAliveThread = new Thread(keepalive);
		keepAliveThread.start();

		while (true) {
			System.out.println("Enter any of the following commands: show-matrix, show-ring, disconnect");
			String input = scanner.nextLine();
			if (input.equalsIgnoreCase("show-matrix")) {
				System.out.println(tracker.getMatrix());
			} else if (input.equalsIgnoreCase("show-ring")) {
				ArrayList<String> output = tracker.getRoute();

				System.out.println("\t");

				for (int i = 0; i < output.size(); i++) {
					if (i != output.size() - 1)
						System.out.print(output.get(i)+" -> ");
					else
						System.out.println(output.get(i));
				}
				System.out.println("");
			} else if (input.equalsIgnoreCase("disconnect")) {
				netIn.interrupt();
				netOut.interrupt();
				scanner.close();
				return;
			} else {
				System.out.println("Sorry, but your input was invalid. Try again.");
			}
		}
		// System.out.println(this.lsa);
	}

	/**
	 * Performs peer discovery using two distinct phases to promote
	 * a higher likelihood of success across an unreliable network.
	 * 
	 * Utilizes two types of packets - ordinary LSA packets which
	 * are used to communicate LSA table entries, and LSA_COMPLETE
	 * packets which are broadcasted across the network to indicate
	 * that this Ringo has successfully discovered all N-1 peers.
	 * 
	 * LSA packets are sent continuously across the network until
	 * a completed LSA table is created, and peer discovery at this 
	 * node is finished.
	 * 
	 * Once discovery is completed for this node, consensus must be 
	 * established to exit this function. This ensures that all nodes
	 * will synchronize all of their network initialization procedures.
	 * Consensus is reached when this node receives an LSA_COMPLETE
	 * packet from all N neighbors. This node simultaneously sends
	 * LSA_COMPLETE packets to all its N-1 neighbors continuously.
	 * 
	 * @param recvQueue - concurrency-safe queue that holds all packets received from the network buffer
	 * @param sendQueue - concurrency-safe queue that holds all packets waiting to be sent from the network buffer
	 */
	private void peerDiscovery(LinkedBlockingQueue<RingoPacket> recvQueue, LinkedBlockingQueue<RingoPacket> sendQueue) {
		Hashtable<String, Boolean> converged = new Hashtable<String, Boolean>();
		this.lsa.put(this.localName+":"+this.localPort, 1);
		if (this.lsa.size() < ringSize) {
			converged.put(this.localName+":"+this.localPort, false);
		} else {
			converged.put(this.localName+":"+this.localPort, true);
		}

		if (this.pocName != "0" && this.pocPort != 0) {
			this.lsa.put(this.pocName+":"+this.pocPort, 1);
			converged.put(this.pocName+":"+this.pocPort, false);

			RingoPacket packet = new RingoPacket(this.localName, this.localPort, this.pocName, this.pocPort, 0, 0, PacketType.LSA, this.role, this.ringSize);
			packet.setLsa(this.lsa);
			sendQueue.add(packet);
		}

		while (!isLsaConverged(converged)) {
			// listen for responses from all nodes
			// and respond with corresponding LSA vectors
			if (!converged.get(this.localName+":"+this.localPort)) {
				try {
					RingoPacket request = recvQueue.take();
					this.lsa.putAll(request.getLsa());

					if (request.getType() != PacketType.LSA_COMPLETE) {
						if (!converged.containsKey(request.getSourceIP()+":"+request.getSourcePort()))
							converged.put(request.getSourceIP()+":"+request.getSourcePort(), false);
					} else {
						converged.put(request.getSourceIP()+":"+request.getSourcePort(), true);
					}

					try {
						Thread.sleep(500);
					} catch (Exception e) {
						System.out.println(e);
					}

					System.out.println(this.lsa);
					// System.out.println("send queue: " +sendQueue);
					// System.out.println("recv queue: " +recvQueue);
					Iterator iter = this.lsa.keySet().iterator();

					while (iter.hasNext()) {
						String key = (String) iter.next();
						//System.out.println("stuff: " +request.getDestIP());
						RingoPacket response = new RingoPacket(this.localName, this.localPort, key.substring(0, key.indexOf(":")), Integer.parseInt(key.substring(key.indexOf(":") + 1)), 0, 0, PacketType.LSA, this.role, this.ringSize);
						response.setLsa(this.lsa);
						sendQueue.add(response);
					}

				} catch (Exception e) {
					// handle later
					System.out.println("this exception is e: " +e);
				}
			} else {
				try {
					RingoPacket request = recvQueue.take();
					this.lsa.putAll(request.getLsa());

					if (request.getType() != PacketType.LSA_COMPLETE) {
						converged.put(request.getSourceIP()+":"+request.getSourcePort(), false);
					} else {
						converged.put(request.getSourceIP()+":"+request.getSourcePort(), true);
					}

					try {
						Thread.sleep(500);
					} catch (Exception e) {
						System.out.println(e);
					}
					
					System.out.println(converged);
					
					Iterator iter = this.lsa.keySet().iterator();

					while (iter.hasNext()) {
						String key = (String) iter.next();
						if (!key.equals(this.localName+":"+this.localPort)) {
							RingoPacket packet = new RingoPacket(this.localName, this.localPort, key.substring(0, key.indexOf(":")), Integer.parseInt(key.substring(key.indexOf(":") + 1)), 0, 0, PacketType.LSA_COMPLETE, this.role, this.ringSize);
							packet.setLsa(this.lsa);
							sendQueue.add(packet);
						}
					}
				} catch (Exception e) {
					System.out.println(e);
				}
			}
		}
	}

	/**
	 * Helper function for consensus phase of peer discovery. Compares 
	 * the elements of the parameter "converged" with this node's LSA
	 * table. If both contain all identical elements, peer discovery
	 * can be considered complete.
	 * 
	 * 
	 * @param converged - data structure containing all nodes that have sent LSA_COMPLETE nodes to this Ringo.
	 * @return true if peer discovery is complete, false otherwise
	 */
	private boolean isLsaConverged(Hashtable<String, Boolean> converged) {
		if (this.lsa.size() >= ringSize) {
			converged.put(this.localName+":"+this.localPort, true);
		} else {
			return false;
		}

		Iterator iter = this.lsa.keySet().iterator();

		while (iter.hasNext()) {
			String key = (String) iter.next();
			// if it's not the localhost
			// if it hasn't converged
			// then we return false
			if (converged.containsKey(key)) {
				if (!key.equals(this.localName+":"+this.localPort) && !converged.get(key)) {
					return false;
				}
			} else {
				return false;
			}
		}

		return true;
	}
	
	/**
	 * Generates an RTT vector between this node and all its N peers.
	 * Uses the PING_REQ and PING_RES type packets to find the RTT value
	 * for a given node. A PING_RES packet always contains a timestamp
	 * indicating when its corresponding PING_REQ request was first sent 
	 * and a timestamp indicating when it was first received at this node. 
	 * The difference of these values results in our RTT value.
	 * 
	 * As with peer discovery, consensus must be established across
	 * the network to finish the RTT vector generation process. This is 
	 * performed in the same way as peer discovery, utilizing
	 * PING_COMPLETE packets to communicate completion.
	 * 
	 * @param recvQueue - concurrency-safe queue that holds all packets received from the network buffer
	 * @param sendQueue - concurrency-safe queue that holds all packets waiting to be sent from the network buffer
	 */
	private void rttVectorGeneration(LinkedBlockingQueue<RingoPacket> recvQueue, LinkedBlockingQueue<RingoPacket> sendQueue) {
		HashSet<String> converged = new HashSet<String>();
		String localkey = this.localName+":"+this.localPort;

		int n = 0;
		this.rttIndex.put(localkey, n);
		this.indexRtt.put(n, localkey);
		this.rtt[this.rttIndex.get(localkey)][this.rttIndex.get(localkey)] = 0;
		n++;

		// ping requests
		//while (!converged.containsAll(this.lsa.keySet())) {
		while(!isRttVectorComplete()) {
			// send packets to all nodes
			Iterator iter = this.lsa.keySet().iterator();

			while (iter.hasNext()) {
				String key = (String) iter.next();
				//System.out.println("rttIndex: " +this.rttIndex);
				if (!this.rttIndex.containsKey(key) && !key.equals(localkey)) {
					RingoPacket requestOut = new RingoPacket(this.localName, this.localPort, key.substring(0, key.indexOf(":")), Integer.parseInt(key.substring(key.indexOf(":") + 1)), 0, 0, PacketType.PING_REQ, this.role, this.ringSize);
					sendQueue.add(requestOut);

					RingoPacket responseIn = null;
					responseIn = takeType(recvQueue, PacketType.PING_RES);

					if (responseIn != null && !this.rttIndex.containsKey(responseIn.getSourceIP()+":"+responseIn.getSourcePort())) {
						assignRtt(responseIn, n, responseIn.getStopTime() - responseIn.getStartTime());
						n++;
					}
					//System.out.println(sendQueue);
				}

				try {
					Thread.sleep(200);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		converged.add(localkey);

		while(!converged.containsAll(this.lsa.keySet())) {
			Iterator iter = this.lsa.keySet().iterator();

			while (iter.hasNext()) {
				String key = (String) iter.next();
				RingoPacket request = new RingoPacket(this.localName, this.localPort, key.substring(0, key.indexOf(":")), Integer.parseInt(key.substring(key.indexOf(":") + 1)), 0, 0, PacketType.PING_COMPLETE, this.role, this.ringSize);
				sendQueue.add(request);

				if (!key.equals(localkey) && !converged.contains(key)) {
					RingoPacket response = null;
					response = takeType(recvQueue, PacketType.PING_COMPLETE);
					if (response != null && !converged.contains(response.getSourceIP()+":"+response.getSourcePort())) {
						converged.add(response.getSourceIP()+":"+response.getSourcePort());
					}
				}

				/*try {
					Thread.sleep(200);
				} catch (Exception e) {
					e.printStackTrace();
				}*/
			}
		}

		for (int i = 0; i < 5; i++) {
			Iterator iter = this.lsa.keySet().iterator();

			while (iter.hasNext()) {
				String key = (String) iter.next();
				RingoPacket request = new RingoPacket(this.localName, this.localPort, key.substring(0, key.indexOf(":")), Integer.parseInt(key.substring(key.indexOf(":") + 1)), 0, 0, PacketType.PING_COMPLETE, this.role, this.ringSize);
				sendQueue.add(request);
			}
			try {
				Thread.sleep(200);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Helper function to identify completion of RTT vector. Checks 
	 * the elements in this node's RTT vector to see if any default values
	 * (in this project they are -1) still exist.
	 * 
	 * @return true if RTT vector is complete, false otherwise
	 */
	private boolean isRttVectorComplete() {
		for (int j = 0; j < this.rtt[0].length; j++) {
			//System.out.println("value in rtt at: " +j+ " is " +this.rtt[0][j]);
			if (this.rtt[0][j] == -1) {
				// not converged for these hosts
				return false;
			}
		}

		return true;
	}

	/**
	 * Multiple data structures are used to maintain the RTT matrix.
	 * These data structures must be used cleanly, at risk of 
	 * damaging this Ringo's network capabilities.
	 * 
	 * This function isolates all code related to RTT vector writing
	 * into a single location.
	 * 
	 * @param packet - packet from which we are writing into our RTT matrix
	 * @param index - index this packet's RTT vector will be assigned in our RTT matrix.
	 * @param rtt - value for RTT from this node and this packet's source node
	 */
	private void assignRtt(RingoPacket packet, int index, long rtt) {
		try {
			InetAddress src = InetAddress.getLocalHost();
			String localkey = src.getHostAddress()+":"+this.localPort;

			this.rttIndex.put(packet.getSourceIP()+":"+packet.getSourcePort(), index);
			this.indexRtt.put(index, packet.getSourceIP()+":"+packet.getSourcePort());

			//this.rtt[rttIndex.get(packet.getSourceIP()+":"+packet.getSourcePort())][rttIndex.get(localkey)] = rtt;
			this.rtt[rttIndex.get(localkey)][rttIndex.get(packet.getSourceIP()+":"+packet.getSourcePort())] = rtt;
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Final phase of network initialization - convergence of RTT matrices
	 * across the network.
	 * 
	 * The logic is modeled in exactly the same way as RTT vector generation.
	 * First we send and receive RTT_REQ and RTT_RES packets to construct
	 * a complete RTT matrix, and then we handle consensus in the same way
	 * as previous phases of initialization.
	 * 
	 * To handle consensus for RTT convergence, we use the RTT_COMPLETE type
	 * packet to signify to peers that our matrix is complete. Consensus
	 * is effectively reached when we receive a unique RTT_COMPLETE packet
	 * from all N-1 peers.
	 * 
	 * @param recvQueue - concurrency-safe queue that holds all packets received from the network buffer
	 * @param sendQueue - concurrency-safe queue that holds all packets waiting to be sent from the network buffer
	 */
	private void rttConvergence(LinkedBlockingQueue<RingoPacket> recvQueue, LinkedBlockingQueue<RingoPacket> sendQueue) {
		HashSet<String> converged = new HashSet<String>();
		String localkey = this.localName+":"+this.localPort;

		HashSet<String> addedToMatrix = new HashSet<String>();
		addedToMatrix.add(this.localName+":"+this.localPort);

		while (!isRttConverged()) {
			Iterator iter = this.lsa.keySet().iterator();

			while (iter.hasNext()) {
				String key = (String) iter.next();

				if (!key.equals(this.localName+":"+this.localPort) && !addedToMatrix.contains(key)) {
					RingoPacket requestOut = new RingoPacket(Ringo.this.localName, Ringo.this.localPort, key.substring(0, key.indexOf(":")), Integer.parseInt(key.substring(key.indexOf(":") + 1)), 0, 0, PacketType.RTT_REQ, Ringo.this.role, this.ringSize);
					sendQueue.add(requestOut);

					//System.out.println("rtt index: " +this.rttIndex);

					RingoPacket responseIn = null;
					responseIn = takeType(recvQueue, PacketType.RTT_RES);

					if (responseIn != null && !addedToMatrix.contains(responseIn.getSourceIP()+":"+responseIn.getSourcePort())) {
						addRttVectorToMatrix(responseIn);
						addedToMatrix.add(responseIn.getSourceIP()+":"+responseIn.getSourcePort());
					}
				}

				try {
					Thread.sleep(400);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		converged.add(localkey);

		while(!converged.containsAll(this.lsa.keySet())) {
			Iterator iter = this.lsa.keySet().iterator();

			while (iter.hasNext()) {
				String key = (String) iter.next();
				RingoPacket request = new RingoPacket(this.localName, this.localPort, key.substring(0, key.indexOf(":")), Integer.parseInt(key.substring(key.indexOf(":") + 1)), 0, 0, PacketType.RTT_COMPLETE, this.role, this.ringSize);
				sendQueue.add(request);

				if (!key.equals(localkey) && !converged.contains(key)) {
					RingoPacket response = null;
					response = takeType(recvQueue, PacketType.RTT_COMPLETE);
					if (response != null && !converged.contains(response.getSourceIP()+":"+response.getSourcePort())) {
						converged.add(response.getSourceIP()+":"+response.getSourcePort());
					}
				}

				/*try {
					Thread.sleep(200);
				} catch (Exception e) {
					e.printStackTrace();
				}*/
			}
		}
	}

	/**
	 * Helper function to identify completion of RTT matrix. Checks 
	 * the elements in this node's RTT matrix to see if any default values
	 * (in this project they are -1) still exist.
	 * 
	 * @return true if RTT matrix is complete, false otherwise
	 */
	private boolean isRttConverged() {
		for (int i = 0; i < this.rtt.length; i++) {
			for (int j = 0; j < this.rtt[0].length; j++) {
				//System.out.println("value in rtt at: " +j+ " is " +this.rtt[0][j]);
				if (this.rtt[i][j] == -1) {
					// not converged for these hosts
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Multiple data structures are used to maintain the RTT matrix.
	 * These data structures must be used cleanly, at risk of 
	 * damaging this Ringo's network capabilities.
	 * 
	 * This function isolates all code related to RTT matrix writing
	 * into a single location.
	 * 
	 * @param packet - packet from which we are writing into our RTT matrix
	 */
	private void addRttVectorToMatrix(RingoPacket packet) {
		String packetKey = packet.getSourceIP()+":"+packet.getSourcePort();
		long [][] packetRtt = packet.getRtt();
		//System.out.println(packetRtt);

		for (int i = 0; i < packetRtt.length; i++) {
			String otherKey = packet.getIndexRtt().get(i);
			if (otherKey == null) {
				return;
			}

			long rttVal = packetRtt[0][i];
			this.rtt[this.rttIndex.get(packetKey)][this.rttIndex.get(otherKey)] = rttVal;
		}

	}

	/**
	 * Used to flush the "queue" parameter of all packets of a specific
	 * type. Helpful when queue is clogged after any network initialization
	 * phase, such as peer discovery.
	 * 
	 * @param queue - concurrency-safe queue that holds all packets for sending or receiving
	 * @param type - type of packet to flush from this queue
	 */
	private void flushType(LinkedBlockingQueue<RingoPacket> queue, PacketType type) {
		Iterator iter = queue.iterator();

		while (iter.hasNext()) {
			RingoPacket packet = (RingoPacket) iter.next();
			if (packet.getType() == type) {
				iter.remove();
			}
		}
	}

	/**
	 * Since the "queue" parameter contains many different types of packets,
	 * it can be inconvenient when trying to access packets of a specific type
	 * due to the FIFO nature of this data structure. We can bypass this 
	 * characteristic of our queue by providing the type we are looking for.
	 * Returns the first packet of PacketType type in the queue.
	 * 
	 * @param queue - concurrency-safe queue that holds all packets for sending or receiving
	 * @param type - type of packet to take from this queue
	 * @return RingoPacket if found, null otherwise
	 */
	private RingoPacket takeType(LinkedBlockingQueue<RingoPacket> queue, PacketType type) {
		Iterator iter = queue.iterator();

		while (iter.hasNext()) {
			RingoPacket packet = (RingoPacket) iter.next();
			if (packet.getType() == type) {
				iter.remove();
				return packet;
			}
		}

		return null;
	}

	/**
	 * Since the "queue" parameter contains many different types of packets,
	 * it can be inconvenient when trying to access a specific packet
	 * due to the FIFO nature of this data structure. We can bypass this 
	 * characteristic of our queue by providing basic information of what
	 * we are looking for.
	 * 
	 * Returns the first packet matching all parameters in this queue.
	 * 
	 * @param queue - concurrency-safe queue that holds all packets for sending or receiving
	 * @param type - type of packet to take from this queue
	 * @param hostname - source hostname of the packet to take from this queue
	 * @param port - source port of the packet to take from this queue
	 * @return RingoPacket if found, null otherwise
	 */
	private RingoPacket takeSpecific(LinkedBlockingQueue<RingoPacket> queue, PacketType type, String hostname, int port) {
		Iterator iter = queue.iterator();

		while (iter.hasNext()) {
			RingoPacket packet = (RingoPacket) iter.next();
			if (packet.getType() == type && packet.getSourceIP().equals(hostname) && packet.getSourcePort() == port) {
				iter.remove();
				return packet;
			}
		}

		return null;
	}

	/**
	 * Contains the function call to a recursive Traveling Salesman Problem
	 * solution. Used to find the "fastest" or optimal path in our ring
	 * network.
	 * 
	 * Converts a list of RTT matrix indices returned from the "recurseNetwork"
	 * into a corresponding list of hostname:port combinations
	 * 
	 * @return ArrayList<String> containing the optimal ring path in "[hostname]:[port]" format
	 */
	public ArrayList<String> generateOptimalRing() {
		Iterator iter = this.lsa.keySet().iterator();
		ArrayList<Long> currRoute = new ArrayList<Long>();
		ArrayList<Long> minRoute = new ArrayList<Long>();

		while (iter.hasNext()) {
			String start = (String) iter.next();
			Set<String> unvisited = new HashSet<String>(this.lsa.keySet());
			Set<String> visited = new HashSet<String>();
			currRoute = recurseNetwork(start, unvisited, visited);

			if (minRoute.size() > 0) {
				if (currRoute.get(0) < minRoute.get(0)) {
					minRoute = currRoute;
				}
			} else {
				minRoute = currRoute;
			}
		}

		ArrayList<String> toReturn = new ArrayList<String>();

		for (int i = 1; i < minRoute.size(); i++) {
			toReturn.add(this.indexRtt.get(minRoute.get(i).intValue()));
		}
		return toReturn;
	}

	/**
	 * Custom Traveling Salesman Problem implementation. Recurses
	 * through network using neighbor list to find the "fastest" route
	 * beginning from the "curr" parameter.
	 * 
	 * @return ArrayList<Long> containing the optimal ring path represented as indices of the RTT matrix
	 */
	private ArrayList<Long> recurseNetwork(String curr, Set<String> unvisited, Set<String> visited) {
		ArrayList<ArrayList<Long>> paths = new ArrayList<ArrayList<Long>>();
		long currIndex = (long) this.rttIndex.get(curr);
		visited.add(curr);

		if (visited.size() == unvisited.size()) {
			ArrayList<Long> toReturn = new ArrayList<Long>();
			toReturn.add((long) 0);
			toReturn.add(currIndex);
			return toReturn;
		}

		Iterator iter = unvisited.iterator();

		// remove curr from unvisited nodes and brute-force search at every neighbor
		while (iter.hasNext()) {
			String neighbor = (String) iter.next();

			if (!visited.contains(neighbor)) {
				//System.out.println("gotta recurse");
				ArrayList<Long> path = recurseNetwork(neighbor, unvisited, visited);
				paths.add(path);
			}
		}

		//System.out.println(paths);
		ArrayList<Long> minPath = paths.get(0);

		for (int i = 1; i < paths.size(); i++) {
			if (paths.get(i).get(0) < minPath.get(0)) {
				minPath = paths.get(i);
			}
		}

		long prevIndex = minPath.get(minPath.size() - 1);
		long rtt = this.rtt[(int) currIndex][(int) prevIndex];
		minPath.set(0, minPath.get(0) + rtt);
		minPath.add(currIndex);

		return minPath;

	}

	/**
	 * This Thread handles all inbound network functions.
	 * Puts all received and serialized packets into parent class
	 * field "recvQueue", a multithreaded data structure.
	 * 
	 * @author sainaidu
	 * @author andrewray
	 */
	private class ReceiverThread implements Runnable {
		LinkedBlockingQueue<RingoPacket> packetQueue;
		LinkedBlockingQueue<RingoPacket> keepAliveQueue;

		private ReceiverThread(LinkedBlockingQueue<RingoPacket> dataQueue, LinkedBlockingQueue<RingoPacket> keepAliveQueue) {
			this.packetQueue = dataQueue;
			this.keepAliveQueue = keepAliveQueue;
		}

		public void run() {
			// loop to track received packets
			while(true) {
				// receiving datagram packets
				try {
					DatagramPacket UDPpacket = receive();
					String sentence = new String(UDPpacket.getData());
					deserializeAndEnqueue(UDPpacket.getData());
				} catch (IOException e) {
					// handle later
				}
			}
		}

		private DatagramPacket receive() throws IOException {
			byte[] data = new byte[10000];
			DatagramPacket packet = new DatagramPacket(data, data.length);
			Ringo.this.socket.receive(packet);
			return packet;
		}

		private void deserializeAndEnqueue(byte [] data) {
			RingoPacket packet = RingoPacket.deserialize(data);
			packet.setStopTime(System.currentTimeMillis());
			if (packet != null) {
				replaceDuplicates(packet);
				if (packet.getType() == PacketType.PING_REQ) {
					RingoPacket responseOut = new RingoPacket(Ringo.this.localName, Ringo.this.localPort, packet.getSourceIP(), packet.getSourcePort(), 0, 0, PacketType.PING_RES, Ringo.this.role, Ringo.this.ringSize);
					responseOut.setStartTime(packet.getStartTime()); // to generate RTT we have to use other packet's start time
					//System.out.println("this is the response I'm returning back boys " +responseOut);
					Ringo.this.sendQueue.add(responseOut);
				} else if (packet.getType() == PacketType.RTT_REQ){
					RingoPacket responseOut = new RingoPacket(Ringo.this.localName, Ringo.this.localPort, packet.getSourceIP(), packet.getSourcePort(), 0, 0, PacketType.RTT_RES, Ringo.this.role, Ringo.this.ringSize);
					responseOut.setRtt(Ringo.this.rtt);
					responseOut.setRttIndex(Ringo.this.rttIndex);
					responseOut.setIndexRtt(Ringo.this.indexRtt);
					Ringo.this.sendQueue.add(responseOut);
				} else if (packet.getType() == PacketType.KEEPALIVE_REQ 
						|| packet.getType() == PacketType.KEEPALIVE_ACK) {
					this.keepAliveQueue.add(packet);
				} else {
					this.packetQueue.add(packet);
				}
			}
		}

		private void replaceDuplicates(RingoPacket packet) {
			Iterator iter = this.packetQueue.iterator();

			while (iter.hasNext()) {
				RingoPacket entry = (RingoPacket) iter.next();

				if (entry.equals(packet)) {
					entry.replace(packet);
					break;
				}
			}

			while (iter.hasNext()) {
				RingoPacket entry = (RingoPacket) iter.next();

				if (entry.equals(packet)) {
					iter.remove();
				}
			}
		}
	}

	/**
	 * This Thread handles all outbound network functions.
	 * Puts all received and serialized packets into parent class
	 * field "recvQueue", a multithreaded data structure.
	 * 
	 * @author sainaidu
	 * @author andrewray
	 */
	private class SenderThread implements Runnable {
		LinkedBlockingQueue<RingoPacket> packetQueue;

		private SenderThread(LinkedBlockingQueue<RingoPacket> packetQueue) {
			this.packetQueue = packetQueue;
		}

		public void run() {
			while(true) {
				RingoPacket packet = dequeue();
				if (packet != null) {
					// System.out.println("current time start: " +System.currentTimeMillis());
					replaceDuplicates(packet);
					packet.setStartTime(System.currentTimeMillis());
					byte [] data = RingoPacket.serialize(packet);
					DatagramPacket udpPacket = createDatagram(data, packet);
					if (udpPacket != null) {
						try {
							Ringo.this.socket.send(udpPacket);
						} catch (Exception e) {
							// handle later
						}
					} else {

					}
				} else {

				}
			}
		}

		private DatagramPacket createDatagram(byte [] data, RingoPacket ringoPacket) {
			try {
				InetAddress dst = InetAddress.getByName(ringoPacket.getDestIP());
				int port = ringoPacket.getDestPort();
				DatagramPacket udppacket = new DatagramPacket(data, data.length, dst, port);
				return udppacket;
			} catch(Exception e) { // if host is unknown
				// handle later
				System.out.println("one exception at this locaiton: " +e);
				return null;
			}
		}

		private RingoPacket dequeue() {
			if (!this.packetQueue.isEmpty()) {
				try {
					RingoPacket packet = this.packetQueue.take();
					return packet;
				} catch (InterruptedException e) {
					// handle this later
					return null;
				}
			} else {
				return null;
			}
		}

		private void replaceDuplicates(RingoPacket packet) {
			Iterator iter = this.packetQueue.iterator();

			while (iter.hasNext()) {
				RingoPacket entry = (RingoPacket) iter.next();

				if (entry.equals(packet)) {
					packet.replace(entry);
					iter.remove();
				}
			}
		}
	}
}
