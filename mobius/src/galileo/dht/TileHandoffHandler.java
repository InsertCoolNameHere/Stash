package galileo.dht;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import galileo.comm.GalileoEventMap;
import galileo.comm.HeartbeatRequest;
import galileo.comm.HeartbeatResponse;
import galileo.dataset.Metadata;
import galileo.dataset.SpatialProperties;
import galileo.dataset.SpatialRange;
import galileo.dht.hash.HashException;
import galileo.event.BasicEventWrapper;
import galileo.event.Event;
import galileo.fs.GeospatialFileSystem;
import galileo.graph.CliqueContainer;
import galileo.graph.TopCliqueFinder;
import galileo.net.ClientMessageRouter;
import galileo.net.GalileoMessage;
import galileo.net.MessageListener;
import galileo.net.NetworkDestination;
import galileo.serialization.SerializationException;
import galileo.util.GeoHash;

/**
 * This class will collect the responses from all the nodes of galileo and then
 * transfers the result to the listener. Used by the {@link StorageNode} class.
 * 
 * @author sapmitra
 */
public class TileHandoffHandler implements MessageListener {

	private static final Logger logger = Logger.getLogger("galileo");
	
	private long WAIT_TIME = 3000l;
	
	// WE ARE GOING TO RETRY FOR NEW HELPER NODES THIS MANY TIMES
	private int RETRY_COUNT = 10;
	
	private boolean waitTimeCheck = true;
	
	private GalileoEventMap eventMap;
	private BasicEventWrapper eventWrapper;
	private ClientMessageRouter router;
	private AtomicInteger expectedResponses;
	private Collection<NetworkDestination> nodes;
	private Map<String, NetworkDestination> nodeStringToNodeMap;
	private List<GalileoMessage> responses;
	private long elapsedTime;
	
	private Map<String, CliqueContainer> topKCliques;
	
	private GeospatialFileSystem fs;
	
	private Partitioner<Metadata> partitioner;
	
	private boolean somethingGotReplicated = false;
	
	// IF THIS IS THE FIRST TIME REQUESTING FOR HELPER NODES
	private boolean firstTry = true;
	
	
	private StorageNode currentNode;
	

	public TileHandoffHandler(List<NetworkDestination> allOtherNodes, GeospatialFileSystem fs, NetworkDestination currentNode, long waitTime, StorageNode thisNode) throws IOException {
		
		this.nodes = allOtherNodes;

		nodeStringToNodeMap = new HashMap<String, NetworkDestination>();
		
		for(NetworkDestination nd : allOtherNodes) {
			
			nodeStringToNodeMap.put(nd.stringRepresentation(), nd);
			
		}
		
		this.router = new ClientMessageRouter(true);
		this.router.addListener(this);
		this.responses = new ArrayList<GalileoMessage>();
		this.eventMap = new GalileoEventMap();
		this.eventWrapper = new BasicEventWrapper(this.eventMap);
		
		this.expectedResponses = new AtomicInteger(0);
		this.currentNode = thisNode;
		this.WAIT_TIME = waitTime;
		
		// FIND TOP N CLIQUES
		// CLIQUE IS THE UNIT OF DATA TRANSFER IN THIS SYSTEM
		topKCliques = TopCliqueFinder.getTopKCliques(fs.getStCache(), fs.getSpatialPartitioningType());
		
		logger.info("TOP CLIQUES: "+ topKCliques);
		
		// CALCULATING THE BITMAP COVERED BY THE TILES IN EACH PARTICULAR CLIQUE
		for(String k : topKCliques.keySet()) {
			
			CliqueContainer clique = topKCliques.get(k);
			
			// POPULATES A BITMAP INSIDE THE CLIQUE CONTAINER
			// STRICTLY MEANT TO BE HOUSED IN THE DISTRESSED NODE
			// NOT MEANT FOR THE HELPING NODE
			clique.calculateBitmap(fs);
			
			
		}
		
		partitioner = fs.getPartitioner();
		
		this.fs = fs;
		
	}

	
	/**
	 * HERE WE HANDLE :
	 * 1) INTERPRETING THE HEARTBEAT MESSAGES
	 * 2) PICKING TOP CLIQUES
	 * 3) SENDING OF CLIQUES TO RESPECTIVE NODES
	 * 4) CREATING ROUTING TABLES
	 * @author sapmitra
	 */
	public void closeRequest() {
		
		int responseCount = 0;
		
		for (GalileoMessage gresponse : this.responses) {
			
			responseCount++;
			
			Event event;
			
			try {
				event = this.eventWrapper.unwrap(gresponse);
				
				if (event instanceof HeartbeatResponse) {
					
					HeartbeatResponse eventResponse = (HeartbeatResponse) event;
					for (int i = 0; i< eventResponse.getDirection().size(); i++) {
						
						logger.info("RIKI: DISTRESS RESPONSE SAYS: "+eventResponse.getResultFlag());
						if(eventResponse.getResultFlag().get(i)) {
							// THIS CLIQUE HAS BEEN REPLICATED SUCCESSFULLY
							// REMOVE THIS CLIQUE FROM TOP CLIQUE
							// ADD THIS ENTRY TO THE ROUTING TABLE
							
							String cliqueKey = eventResponse.getGeohashOfClique().get(i);
							
							String nodeKey = eventResponse.getNodeString();
							
							NetworkDestination nd = nodeStringToNodeMap.get(nodeKey);
							topKCliques.get(cliqueKey).setReplicatedNode(nd);
							
							somethingGotReplicated = true;
							
						} else {
							// FAILED TO REPLICATE THIS, TRY ANOTHER NODE
							// KEEP THIS ENTRY IN TOP CLIQUES
							// CALCULATE THE NEXT ANTIPODE GEOHASH
							
							
						}
						
					}
					
				}
			} catch (IOException | SerializationException e) {
				logger.log(Level.SEVERE, "An exception occurred while processing the response message. Details follow:"
						+ e.getMessage(), e);
			} catch (Exception e) {
				logger.log(Level.SEVERE, "An unknown exception occurred while processing the response message. Details follow:"
								+ e.getMessage(), e);
			}
		}
		
		int totalHomesFound = 0;
		
		for(CliqueContainer clique : topKCliques.values()) {

			if(clique.getReplicatedNode() != null)
				totalHomesFound++;
		}
		
		// IF NOT ALL CLIQUES HAVE FOUND A HOME, REDO THE PROCESS
		if(topKCliques.size() != totalHomesFound && RETRY_COUNT >= 0) {
			
			logger.info("RIKI: NOT ALL CLIQUES FOUND HOME....RETRYING FOR REMAINING CLIQUES");
			RETRY_COUNT--;
			handleRequest();
			
		} else {
			
			logger.info("RIKI: DISTRESS RESPONSESD GETTING LOADED IN ROUTING TABLE");
			silentClose(); // closing the router to make sure that no new responses are added.
			
			// POPULATE ROUTING TABLE WITH CLIQUES THAT GOT REPLICATED
			fs.populateRoutingTable(topKCliques);
			
			// IF AT ALL SOMETHING GOT REPLICATED
			if(somethingGotReplicated)
				currentNode.setHotspotHasBeenHandledTime(System.currentTimeMillis());
			
			logger.info("RIKI: HEARTBEAT COMPILED WITH "+responseCount+" MESSAGES");
			
			currentNode.setHotspotBeingHandled(false);
			
		}
		
		
		
		/*try {
			afterHeartbeatCheck();
		} catch (HashException | PartitionException e) {
			// TODO Auto-generated catch block
			logger.severe("RIKI: ERROR AFTER SUCCESSFUL HEARTBEAT");
		}*/
		
	}


	@Override
	public void onMessage(GalileoMessage message) {
		
		logger.info("RIKI: HEARTBEAT RESPONSE RECEIVED");
		
		if (null != message)
			this.responses.add(message);
		
		int awaitedResponses = this.expectedResponses.decrementAndGet();
		logger.log(Level.INFO, "Awaiting " + awaitedResponses + " more message(s)");
		
		
		if (awaitedResponses <= 0) {
			// PREVENT TIMEOUTCHECKER FROM MEDDLING
			this.waitTimeCheck = false;
			this.elapsedTime = System.currentTimeMillis() - this.elapsedTime;
			
			closeRequest();
				
		}
	}
	
	/**
	 * Given a geohash, find the node that houses it
	 * @author sapmitra
	 * @param geoHashAntipode
	 * @return
	 */
	public NodeInfo getNodeForGeoHash(String geoHashAntipode) {
		
		// FINDING THE NODE THAT HOUSES THE ANTIPODE GEOHASH
		SpatialRange spatialRange = GeoHash.decodeHash(geoHashAntipode);
		
		SpatialProperties spatialProperties = new SpatialProperties(spatialRange);
		Metadata metadata = new Metadata();
		metadata.setName(geoHashAntipode);
		metadata.setSpatialProperties(spatialProperties);
		
		NodeInfo targetNode = null;
		try {
			targetNode = partitioner.locateData(metadata);
		} catch (HashException | PartitionException e) {
			logger.severe("RIKI: CANNOT FIND DESTINATION FOR GEOHASH: "+ geoHashAntipode);
		}
	
		//String nodeString = targetNode.stringRepresentation();
		
		return targetNode;
		
	}

	/**
	 * Handles the client request on behalf of the node that received the
	 * request
	 * 
	 * @param request
	 *            - This must be a server side event: Generic Event or
	 *            QueryEvent
	 * @param nr_current 
	 * @param hostString 
	 * @param response
	 */
	public void handleRequest() {
		
		try {
			
			/**
			 * STEP 1: FIND TOP K CLIQUES IN THE NODE
			 */
			Map<String, List<RoutingEntry>> routingTable = fs.getRoutingTable();
			
			// MAP OF WHICH CLIQUE GOES TO WHICH NODE
			Map<String, List<CliqueContainer>> nodeToCliquesMap = new HashMap<String, List<CliqueContainer>>();
			
			if(topKCliques.size() <= 0) {
				
				currentNode.setHotspotBeingHandled(false);
				return;
			}
			
			// FOR EACH CLIQUE, FIND A SUITABLE NODE TO SEND IT TO
			// THE NODE HAS TO BE THE ANTIPODE OF THE GEOHASH IN QUESTION
			for(Entry<String, CliqueContainer> entry : topKCliques.entrySet()) {
				
				boolean keepLooping = true;
				
				// THE TARGET HELPER NODE NODE
				String nodeString = null;
				
				while(keepLooping) {
					
					// EAST OR WEST
					int randDirection = ThreadLocalRandom.current().nextInt(3,5);
					
					String geohashKey = entry.getKey();
					CliqueContainer clique = entry.getValue();
					
					// IN CASE OF RETRY, AVOID CLIQUES THAT HAVE ALREADY FOUND A HOME
					if(clique.getReplicatedNode() != null)
						continue;
					
					String geoHashAntipode = GeoHash.getAntipodeGeohash(geohashKey);
					
					NodeInfo targetNode = null;
					
					if(!firstTry) {
						
						// GET THE GEOHASH ANTIPODE
						// GET ITS NEIGHBOR
						// LOOP TILL THE NODE IS DIFFERENT FROM THE OLD ANTIPODE
						
						randDirection = clique.getDirection();
						
						geoHashAntipode = clique.getGeohashAntipode();
						
						String newGeohashAntipode = geoHashAntipode;
						
						NodeInfo tempTargetNode = getNodeForGeoHash(geoHashAntipode);
						
						String oldNode = tempTargetNode.stringRepresentation();
						
						String newNode = oldNode;
						
						while(newNode.equals(oldNode)) {
							// KEEP SHIFTING TILL A NEW CANDIDATE NODE HAS BEEN FOUND
							newGeohashAntipode = GeoHash.getNeighbours(newGeohashAntipode)[randDirection];
							
							tempTargetNode = getNodeForGeoHash(newGeohashAntipode);
							newNode = tempTargetNode.stringRepresentation();
						}
						
						targetNode = tempTargetNode;
						
					} else {
						
						// TILL A SUITABLE NODE HAS BEEN FOUND
						
						
						// FINDING THE NODE THAT HOUSES THE ANTIPODE GEOHASH
						SpatialRange spatialRange = GeoHash.decodeHash(geoHashAntipode);
						
						SpatialProperties spatialProperties = new SpatialProperties(spatialRange);
						Metadata metadata = new Metadata();
						metadata.setName(geoHashAntipode);
						metadata.setSpatialProperties(spatialProperties);
						
						try {
							targetNode = partitioner.locateData(metadata);
						} catch (HashException | PartitionException e) {
							logger.severe("RIKI: CANNOT FIND ANTIPODE DESTINATION");
						}
						
					}
					
					nodeString = targetNode.stringRepresentation();
					
					// KEEPS TRACK OF WHICH ANTIPODE IS CURRENTLY BEING DEALT WITH
					clique.setGeohashAntipode(geoHashAntipode);
					clique.setDirection(randDirection);
					
					logger.info("RIKI: CLIQUE: "+geohashKey +" HAS AN ANTIPODE "+geoHashAntipode+" AND CORRESPONDING HELPER NODE IS "+ nodeString);
					
					keepLooping = checkIfEntryExistsInRoutingTable(routingTable, nodeString, geohashKey);
					
					if(keepLooping) {
						
						logger.info("RIKI: WE NEED TO KEEP LOOKING FOR A NEW HELPER NODE FOR CLIQUE: "+geohashKey);
						continue;
						
					} else {
						if(nodeToCliquesMap.get(nodeString) == null) {
							
							List<CliqueContainer> cliques = new ArrayList<CliqueContainer>();
							
							cliques.add(clique);
							nodeToCliquesMap.put(nodeString, cliques);
						} else {
							
							List<CliqueContainer> cliques = nodeToCliquesMap.get(nodeString);
							cliques.add(clique);
						}
						break;
					}
				}
				
				
			}
			
			
			expectedResponses = new AtomicInteger(nodeToCliquesMap.size());
			
			
			firstTry = false;
			// USE nodeToCliquesMap TO DIRECT CLIQUES TO RESPECTIVE NODES
			// IF THE NODES DONT SEND BACK POSITIVE ACK, TAKE THE REMAINING CLIQUES AND PERFORM THIS SAME SEQUENCE AGAIN
			
			
			
			for(Entry<String, List<CliqueContainer>> entry : nodeToCliquesMap.entrySet()) {
				
				long eventTime = System.currentTimeMillis();
				String nodeKey = entry.getKey();
				List<CliqueContainer> cliquesToSend = entry.getValue();
				
				NetworkDestination nodeToSendTo = nodeStringToNodeMap.get(nodeKey);
				
				HeartbeatRequest hr = new HeartbeatRequest(cliquesToSend, currentNode.getNodeString(), eventTime);
				
				GalileoMessage mrequest = this.eventWrapper.wrap(hr);
				
				this.router.sendMessage(nodeToSendTo, mrequest);
				logger.info("RIKI: HEARTBEAT REQUEST SENT TO " + nodeToSendTo.toString());
				
				
				
			}
			
			// CHECKS IF THERE IS A TIMEOUT IN RESPONSE COMING BACK FROM THE HELPER NODES
			TimeoutChecker tc = new TimeoutChecker(this, WAIT_TIME);
			Thread internalThread = new Thread(tc);
			
			internalThread.start();
			
		} catch (IOException e) {
			logger.log(Level.INFO,
					"Failed to send request to other nodes in the network. Details follow: " + e.getMessage());
		}
		
	}

	
	/**
	 * CHECKS IF A PARTICULAR NODE ALREADY HOUSES A PARTICULAR CLIQUE ALREADY
	 * IF SO, FIND ANOTHER NODE TO REPLICATE IN....TO MAKE MULTIPLE COPIES OF THE REPLICA
	 * 
	 * @author sapmitra
	 * @param routingTable
	 * @param nodeString
	 * @param geohashKey
	 * @return
	 */
	private boolean checkIfEntryExistsInRoutingTable(Map<String, List<RoutingEntry>> routingTable, String nodeString,
			String geohashKey) {
		
		
		if(routingTable.containsKey(geohashKey)) {
			
			List<RoutingEntry> relist = routingTable.get(geohashKey);
			if(relist != null && relist.size()>0) {
				for(RoutingEntry re : relist) {
					
					if(re.getHelperNode().stringRepresentation().equals(nodeString)) {
						return true;
					}
				}
			}
			
		}
		return false;
	}


	public void silentClose() {
		try {
			this.router.forceShutdown();
		} catch (Exception e) {
			logger.log(Level.INFO, "Failed to shutdown the completed client request handler: ", e);
		}
	}

	@Override
	public void onConnect(NetworkDestination endpoint) {

	}

	@Override
	public void onDisconnect(NetworkDestination endpoint) {

	}

	public boolean isWaitTimeCheck() {
		return waitTimeCheck;
	}

	public void setWaitTimeCheck(boolean waitTimeCheck) {
		this.waitTimeCheck = waitTimeCheck;
	}
	
	

}
