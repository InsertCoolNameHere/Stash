package galileo.dht;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import galileo.comm.DataIntegrationFinalResponse;
import galileo.comm.DataIntegrationResponse;
import galileo.comm.GalileoEventMap;
import galileo.comm.HeartbeatResponse;
import galileo.comm.MetadataResponse;
import galileo.comm.QueryResponse;
import galileo.comm.VisualizationEventResponse;
import galileo.comm.VisualizationResponse;
import galileo.dataset.Metadata;
import galileo.dataset.SpatialHint;
import galileo.dataset.SpatialProperties;
import galileo.dataset.SpatialRange;
import galileo.dht.hash.HashException;
import galileo.event.BasicEventWrapper;
import galileo.event.Event;
import galileo.event.EventContext;
import galileo.fs.GeospatialFileSystem;
import galileo.graph.CliqueContainer;
import galileo.graph.SummaryStatistics;
import galileo.graph.SummaryWrapper;
import galileo.graph.TopCliqueFinder;
import galileo.net.ClientMessageRouter;
import galileo.net.GalileoMessage;
import galileo.net.MessageListener;
import galileo.net.NetworkDestination;
import galileo.net.RequestListener;
import galileo.serialization.SerializationException;
import galileo.util.GeoHash;

/**
 * This class will collect the responses from all the nodes of galileo and then
 * transfers the result to the listener. Used by the {@link StorageNode} class.
 * 
 * @author sapmitra
 */
public class NodeInfoRequestHandler implements MessageListener {

	private static final Logger logger = Logger.getLogger("galileo");
	
	private long WAIT_TIME = 0l;
	private boolean waitTimeCheck = true;
	
	private GalileoEventMap eventMap;
	private BasicEventWrapper eventWrapper;
	private ClientMessageRouter router;
	private AtomicInteger expectedResponses;
	private Collection<NetworkDestination> nodes;
	private List<GalileoMessage> responses;
	private Event response;
	private long elapsedTime;
	private GeospatialFileSystem fs;
	
	
	private NetworkDestination currentNode;
	

	public NodeInfoRequestHandler(List<NetworkDestination> allOtherNodes, GeospatialFileSystem fs, NetworkDestination currentNode, long waitTime) throws IOException {
		
		
		this.nodes = allOtherNodes;

		this.router = new ClientMessageRouter(true);
		this.router.addListener(this);
		this.responses = new ArrayList<GalileoMessage>();
		this.eventMap = new GalileoEventMap();
		this.eventWrapper = new BasicEventWrapper(this.eventMap);
		
		this.expectedResponses = new AtomicInteger(this.nodes.size());
		this.fs = fs;
		this.currentNode = currentNode;
		this.WAIT_TIME = waitTime;
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
		
		silentClose(); // closing the router to make sure that no new responses are added.
		
		int responseCount = 0;
		
		for (GalileoMessage gresponse : this.responses) {
			
			responseCount++;
			Event event;
			
			try {
				event = this.eventWrapper.unwrap(gresponse);
				
				if (event instanceof HeartbeatResponse) {
					
					HeartbeatResponse eventResponse = (HeartbeatResponse) event;
					
					logger.info("RIKI: HEARTBEAT RESPONSE RECEIVED....FROM " + eventResponse.getHostString());
					
					NodeResourceInfo nr = new NodeResourceInfo(eventResponse.getCpuUtil(), eventResponse.getGuestTreeSize(),
							eventResponse.getHeapMem());
					
					// THE HOSTSTRING IS HOSTNAME:PORT....CAN CREATE NODEINFO FROM IT
					nodesResourceMap.put(eventResponse.getHostString(), nr);
				
				}
			} catch (IOException | SerializationException e) {
				logger.log(Level.SEVERE, "An exception occurred while processing the response message. Details follow:"
						+ e.getMessage(), e);
			} catch (Exception e) {
				logger.log(Level.SEVERE, "An unknown exception occurred while processing the response message. Details follow:"
								+ e.getMessage(), e);
			}
		}
			
		
		logger.info("RIKI: HEARTBEAT COMPILED WITH "+responseCount+" MESSAGES");
		
		try {
			afterHeartbeatCheck();
		} catch (HashException | PartitionException e) {
			// TODO Auto-generated catch block
			logger.severe("RIKI: ERROR AFTER SUCCESSFUL HEARTBEAT");
		}
		
	}

	
	/**
	 * ONCE HEARTBEAT RESPONSES HAVE BEEN ACCUMULATED
	 * @author sapmitra
	 * @throws PartitionException 
	 * @throws HashException 
	 */
	private void afterHeartbeatCheck() throws HashException, PartitionException {
		
		// FIND TOP N CLIQUES
		Map<String, CliqueContainer> topKCliques = TopCliqueFinder.getTopKCliques(fs.getStCache(), fs.getSpatialPartitioningType());
		
		// MAP OF WHICH CLIQUE GOES TO WHICH NODE
		Map<String, List<CliqueContainer>> nodeToCliquesMap = new HashMap<String, List<CliqueContainer>>();
		
		// FOR EACH CLIQUE, FIND A SUITABLE NODE TO SEND IT TO
		// THE NODE HAS TO BE THE ANTIPODE OF THE GEOHASH IN QUESTION
		for(Entry<String, CliqueContainer> entry : topKCliques.entrySet()) {
			
			String geohashKey = entry.getKey();
			CliqueContainer clique = entry.getValue();
			
			
			String geoHashAntipode = GeoHash.getAntipodeGeohash(geohashKey);
			
			boolean looking = true;
			
			int shift = 0;
			
			// EAST OR WEST
			int randDirection = ThreadLocalRandom.current().nextInt(3,5);
			
			// TILL A SUITABLE NODE HAS BEEN FOUND
			while(looking) {
				
				Partitioner<Metadata> partitioner = fs.getPartitioner();
				
				SpatialRange spatialRange = GeoHash.decodeHash(geoHashAntipode);
				
				SpatialProperties spatialProperties = new SpatialProperties(spatialRange);
				Metadata metadata = new Metadata();
				metadata.setName(geoHashAntipode);
				metadata.setSpatialProperties(spatialProperties);
				
				NodeInfo targetNode = partitioner.locateData(metadata);
				
				String nodeString = targetNode.stringRepresentation();
				NodeResourceInfo nodeResourceInfo = nodesResourceMap.get(nodeString);
				
				shift++;
				
				if(nodeResourceInfo.getGuestTreeSize() > clique.getTotalCliqueSize()) {
					
					looking = false;
					
					nodeResourceInfo.decrementGuestTreeSize(clique.getTotalCliqueSize());
					
					// ASSIGN THIS CLIQUE TO THIS NODE
					if(nodeToCliquesMap.get(nodeString) == null) {
						
						List<CliqueContainer> cliques = new ArrayList<CliqueContainer>();
						cliques.add(clique);
						nodeToCliquesMap.put(nodeString, cliques);
					} else {
						
						List<CliqueContainer> cliques = nodeToCliquesMap.get(nodeString);
						cliques.add(clique);
					}
					
					
				} else {
					// WE NEED TO FIND ANOTHER NODE
					
					geoHashAntipode = GeoHash.getNeighbours(geoHashAntipode)[randDirection];
					
					if(shift > Math.pow(2, geohashKey.length()*3)) {
						looking = false;
					}
					
				}
			}
			
		}
		
		// USE nodeToCliquesMap TO DIRECT CLIQUES TO RESPECTIVE NODES
		// IF THE NODES DONT SEND BACK POSITIVE ACK, TAKE THE REMAINING CLIQUES AND PERFORM THIS SAME SEQUENCE AGAIN
		
		
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
			
			// FIND TOP N CLIQUES
			// CLIQUE IS THE UNIT OF DATA TRANSFER IN THIS SYSTEM
			Map<String, CliqueContainer> topKCliques = TopCliqueFinder.getTopKCliques(fs.getStCache(), fs.getSpatialPartitioningType());
			
			// MAP OF WHICH CLIQUE GOES TO WHICH NODE
			Map<String, List<CliqueContainer>> nodeToCliquesMap = new HashMap<String, List<CliqueContainer>>();
			
			// FOR EACH CLIQUE, FIND A SUITABLE NODE TO SEND IT TO
			// THE NODE HAS TO BE THE ANTIPODE OF THE GEOHASH IN QUESTION
			for(Entry<String, CliqueContainer> entry : topKCliques.entrySet()) {
				
				// EAST OR WEST
				int randDirection = ThreadLocalRandom.current().nextInt(3,5);
				
				String geohashKey = entry.getKey();
				CliqueContainer clique = entry.getValue();
				
				String geoHashAntipode = GeoHash.getAntipodeGeohash(geohashKey);
				
				// TILL A SUITABLE NODE HAS BEEN FOUND
					
				Partitioner<Metadata> partitioner = fs.getPartitioner();
				
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
					logger.severe("RIKI: CANNOT FIND ANTIPODE DESTINATION");
				}
				
				String nodeString = targetNode.stringRepresentation();
				
				// KEEPS TRACK OF WHICH ANTIPODE IS CURRENTLY BEING DEALT WITH
				clique.setGeohashAntipode(geoHashAntipode);
				clique.setDirection(randDirection);
				
				if(nodeToCliquesMap.get(nodeString) == null) {
					
					List<CliqueContainer> cliques = new ArrayList<CliqueContainer>();
					
					cliques.add(clique);
					nodeToCliquesMap.put(nodeString, cliques);
				} else {
					
					List<CliqueContainer> cliques = nodeToCliquesMap.get(nodeString);
					cliques.add(clique);
				}
				
				
			}
			
			// USE nodeToCliquesMap TO DIRECT CLIQUES TO RESPECTIVE NODES
			// IF THE NODES DONT SEND BACK POSITIVE ACK, TAKE THE REMAINING CLIQUES AND PERFORM THIS SAME SEQUENCE AGAIN
			
			
			
			for(Entry<String, List<CliqueContainer>> entry : nodeToCliquesMap.entrySet()) {
				
				String nodeKey = entry.getKey();
				List<CliqueContainer> cliquesToSend = entry.getValue();
				
				
			}
			
			
			
			
			
			
			
			
			
			
			
			
			
			
			GalileoMessage mrequest = this.eventWrapper.wrap(request);
			
			for (NetworkDestination node : nodes) {
				
				this.router.sendMessage(node, mrequest);
				logger.info("RIKI: HEARTBEAT REQUEST SENT TO " + node.toString());
				
			}
			
			this.elapsedTime = System.currentTimeMillis();
			
			// CHECKS IF THERE IS A TIMEOUT IN RESPONSE COMING BACK FROM THE HELPER NODES
			TimeoutChecker tc = new TimeoutChecker(this, WAIT_TIME);
			Thread internalThread = new Thread(tc);
			
			internalThread.start();
			
		} catch (IOException e) {
			logger.log(Level.INFO,
					"Failed to send request to other nodes in the network. Details follow: " + e.getMessage());
		}
		
		
		
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