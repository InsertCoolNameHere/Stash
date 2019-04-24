package galileo.bq;
/* 
 * 
 * All rights reserved.
 * 
 * CSU EDF Project
 * 
 * This program read a csv-formatted file and send each line to the galileo server
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import galileo.client.EventPublisher;
import galileo.comm.GalileoEventMap;
import galileo.comm.QueryResponse;
import galileo.comm.VisualizationRequest;
import galileo.comm.VisualizationResponse;
import galileo.comm.WipeCacheRequest;
import galileo.dataset.Coordinates;
import galileo.event.BasicEventWrapper;
import galileo.event.Event;
import galileo.event.EventWrapper;
import galileo.graph.SpatiotemporalHierarchicalCache;
import galileo.graph.SummaryWrapper;
import galileo.net.ClientMessageRouter;
import galileo.net.GalileoMessage;
import galileo.net.MessageListener;
import galileo.net.NetworkDestination;

public class VisualizationNAMThroughputTest implements MessageListener {
	
	private static boolean cachingOn = true;
	private static boolean randomOn = true;
	private static float halfFrac = 0.05f;
	
	private static int rspCount = 0;
	
	private static long startTime = 0;
	private static long endTime = 0;
	
	private static int group = 100;
	private static int each = 100;
	
	//private static boolean cacheInitialized = false;
	
	private static GalileoEventMap eventMap = new GalileoEventMap();
	private static EventWrapper wrapper = new BasicEventWrapper(eventMap);
	
	
	//public static SpatiotemporalHierarchicalCache localCache;
	
	@Override
	public void onConnect(NetworkDestination endpoint) {
	}

	@Override
	public void onDisconnect(NetworkDestination endpoint) {
	}

	@Override
	public void onMessage(GalileoMessage message) {
		try {
			Event unwrap = wrapper.unwrap(message);
			if(unwrap instanceof VisualizationResponse) {
				
				if(rspCount == 0)
					startTime = System.currentTimeMillis();
				rspCount++;
				VisualizationResponse response = (VisualizationResponse) wrapper.unwrap(message);
				
				//List<String> keys = response.getKeys();
				List<SummaryWrapper> summaries = response.getSummaries();
				
				if(rspCount % 100 == 0)
					System.out.println(summaries.size()+"================"+rspCount+"======"+(System.currentTimeMillis() - startTime));
				//System.out.println(keys);
				/*for(int i=0; i < summaries.size(); i++) {
					System.out.println(keys.get(i));
					System.out.println(summaries.get(i));
					System.out.println("=================");
				}*/
			} else if(unwrap instanceof QueryResponse) {
				QueryResponse rsp = (QueryResponse) unwrap;
				
				System.out.println("SOMETHING WENT WRONG " + rsp.getJSONResults());
			}
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	
	// [START processFile]
	/**
	 * read each line from the csv file and send it to galileo server
	 * @param k 
	 * @param j 
	 * 
	 * @param pathtothefile
	 *            path to the csv file
	 * @param galileoconnector
	 *            GalileoConnector instance
	 * @throws Exception
	 */
	
	private static void processRequest(List<GalileoConnector> gcs, int sl, int tl, String querySize) throws Exception {
		
		/*if(cachingOn) {
			if(!cacheInitialized) {
				initializeCache(sl, tl);
				cacheInitialized = true;
			}
		}*/
		
		try {
			
			ClientMessageRouter messageRouter = new ClientMessageRouter();
			VisualizationNAMThroughputTest vqt = new VisualizationNAMThroughputTest();
			
			messageRouter.addListener(vqt);
			
			
			List<VisualizationRequest> reqs = new ArrayList<VisualizationRequest>();
			
			if(randomOn)
				reqs = createRandomVisualizationRequest(querySize,sl,tl);
			else 
				//reqs = createVisualizationRequest(querySize, sl, tl);
				reqs = null;
			
			
			//System.out.println(reqs);
			//Thread.sleep(10*1000);
			
			int count = 0;
			for(VisualizationRequest randomVizReq : reqs) {
				
				if(!cachingOn)
					randomVizReq.setCachingOn(cachingOn);
				
				//gc.visualize(vr);
				//messageRouter.sendMessage(gc.server, EventPublisher.wrapEvent(createRandomVisualizationRequest(querySize, sl, tl)));
				
				int indx = ThreadLocalRandom.current().nextInt(0,100);
				GalileoConnector gg = gcs.get(indx);
				
				System.out.println("REQUESTING: "+ gg.server +" WITH POLYGON :" + randomVizReq.getPolygon());
				
				messageRouter.sendMessage(gg.server, EventPublisher.wrapEvent(randomVizReq));
				
				
				Thread.sleep(1);
				
				if(count == 100) {
					//Thread.sleep(500);
					count = 0;
				} else {
					//Thread.sleep(5);
				}
				
				count++;
				
			}
			
			System.out.println("FINISHED REQUESTING....WAITING FOR RESPONSES");
			Thread.sleep(2000*1000);
		
			
		} finally {
			
			Thread.sleep(1*1000);
			
			for(GalileoConnector gc : gcs) {
				gc.disconnect();
			}
			
			System.out.println("EXITING....");
		}
	}
	
	public static List<Coordinates> getRandomCoordinates(String polygonSize) {
		
		List<Coordinates> cl = new ArrayList<Coordinates>();
		// Country Wide
		float latLength = 15f;
		float longLength = 30f;
		
		if(polygonSize.equalsIgnoreCase("country")) {
			latLength = 16f;
			longLength = 32f;
			
		} else if(polygonSize.equalsIgnoreCase("state")) {
			latLength = 4f;
			longLength = 8f;
			
		} else if(polygonSize.equalsIgnoreCase("county")) {
			latLength = 0.5f;
			longLength = 1.0f;
			
		} else if(polygonSize.equalsIgnoreCase("city")) {
			latLength = 0.2f;
			longLength = 0.4f;
		}
		
		float startLat = 27f;
		float endLat = 48f;
		
		float startLong = -130.0f;
		float endLong = -69.0f;
		
		float lowLat = (float)ThreadLocalRandom.current().nextDouble(startLat, endLat - latLength);
		
		while(lowLat+latLength > endLat) {
			lowLat = (float)ThreadLocalRandom.current().nextDouble(startLat, endLat - latLength);
		}
		
		float lowLong = (float)ThreadLocalRandom.current().nextDouble(startLong, endLong - longLength);
		
		while(lowLong+longLength > endLong) {
			lowLong = (float)ThreadLocalRandom.current().nextDouble(startLong, endLong - longLength);
		}
		//int date = ThreadLocalRandom.current().nextInt(1, 15);
		
		if(lowLat+latLength <= endLat && lowLong+longLength <= endLong) {
		
			Coordinates c1 = new Coordinates(lowLat+latLength, lowLong);
			Coordinates c2 = new Coordinates(lowLat+latLength, lowLong+longLength);
			Coordinates c3 = new Coordinates(lowLat, lowLong+longLength);
			Coordinates c4 = new Coordinates(lowLat, lowLong);
			
			cl.add(c1); cl.add(c2); cl.add(c3); cl.add(c4);
			
		}
			
		System.out.println("COORDINATES: "+cl);
		return cl;
		
	}
	
	
	public static List<VisualizationRequest> createRandomVisualizationRequest(String polygonSize, int sl, int tl) {
		
		List<VisualizationRequest> reqs = new ArrayList<VisualizationRequest>();
		
		float startLat = 15f;
		float endLat = 48f;
		
		float startLong = -130.0f;
		float endLong = -69.0f;
		
		
		// Country Wide
		float latLength = 16f;
		float longLength = 32f;
		
		if(polygonSize.equalsIgnoreCase("country")) {
			latLength = 15f;
			longLength = 30f;
			
		} else if(polygonSize.equalsIgnoreCase("state")) {
			latLength = 4f;
			longLength = 8f;
			
		} else if(polygonSize.equalsIgnoreCase("county")) {
			latLength = 0.5f;
			longLength = 1.0f;
			
		} else if(polygonSize.equalsIgnoreCase("city")) {
			latLength = 0.2f;
			longLength = 0.4f;
		}
		
		for(int i=0; i < group; i++) {
			
			//System.out.println("******************************"+(i+1));
			
			float lat1 = (float)ThreadLocalRandom.current().nextDouble(startLat, endLat - latLength);
			
			while(lat1+latLength > endLat) {
				lat1 = (float)ThreadLocalRandom.current().nextDouble(startLat, endLat - latLength);
			}
			
			float lat2 = (float)ThreadLocalRandom.current().nextDouble(startLong, endLong - longLength);
			
			while(lat2+longLength > endLong) {
				lat2 = (float)ThreadLocalRandom.current().nextDouble(startLong, endLong - longLength);
			}
			
			reqs.addAll(createVisualizationRequest(polygonSize, sl, tl, lat1, lat2));
			
		}
		
		
		return reqs;
	}
	
	
	
	public static List<VisualizationRequest> createVisualizationRequest(String polygonSize, int sl, int tl, float Olat1, float Olon1) {
		
		
		List<VisualizationRequest> reqs = new ArrayList<VisualizationRequest>();
		
		float lat1 = Olat1;
		float lon1 = Olon1;
		
		
		// Country Wide
		float latLength = 16f;
		float longLength = 32f;
		
		if(polygonSize.equalsIgnoreCase("country")) {
			latLength = 15f;
			longLength = 30f;
			
		} else if(polygonSize.equalsIgnoreCase("state")) {
			latLength = 4f;
			longLength = 7f;
			
		} else if(polygonSize.equalsIgnoreCase("county")) {
			latLength = 0.5f;
			longLength = 1.0f;
			
		} else if(polygonSize.equalsIgnoreCase("city")) {
			latLength = 0.2f;
			longLength = 0.4f;
		}
		
		
		// THE SUMMARIES WE ARE REQUESTING
		List<String> sumHints = new ArrayList<String>();
		sumHints.add("geopotential_height_lltw");
		sumHints.add("water_equiv_of_accum_snow_depth_surface");
		sumHints.add("drag_coefficient_surface");
		sumHints.add("v-component_of_wind_tropopause");
		sumHints.add("downward_short_wave_rad_flux_surface");
		sumHints.add("u-component_of_wind_maximum_wind");
		
		
		for(int i=0; i < each; i++) {
			
			//System.out.println("******************************"+(i+1));
			List<Coordinates> cl = new ArrayList<Coordinates>();
			float lat2 = lat1+latLength;
			float lon2 = lon1+longLength;
			
			VisualizationRequest vr = new VisualizationRequest(); 
			vr.setFsName("namfs");
			
			Coordinates c1 = new Coordinates(lat2, lon1);
			Coordinates c2 = new Coordinates(lat2, lon2);
			Coordinates c3 = new Coordinates(lat1, lon2);
			Coordinates c4 = new Coordinates(lat1, lon1);
			//Coordinates c5 = new Coordinates(36.78f, -107.64f);
			
			cl.add(c1); cl.add(c2); cl.add(c3); cl.add(c4);
			
			//System.out.println("COORDINATES: "+cl);
			
			vr.setPolygon(cl);
			vr.setTime("2013-02-02-xx");
			
			vr.setSpatialResolution(sl);
			vr.setTemporalResolution(tl);
			
			
			vr.setReqFeatures(sumHints);
			
			reqs.add(vr);
			
			int m1 = ThreadLocalRandom.current().nextInt(-4,4);
			int m2 = ThreadLocalRandom.current().nextInt(-4,4);
			
			lat1 = m1*halfFrac*latLength + Olat1;
			lon1 = m2*halfFrac*longLength + Olon1;
			
		}
		
		
		
		/*System.out.println("COORDINATES: "+cl);*/
		
		
		/*vr.setSpatialResolution(2);
		vr.setTemporalResolution(2);*/
		
		
		return reqs;
	}
	
	
	/*public static void main1(String arg[]) {
		
		for (int i = 101; i <= 220; i++) {
			System.out.println("lattice-"+i);
		}
		
	}*/
	
	/*public static void initializeCache(int sl, int tl) {
		
		localCache = new SpatiotemporalHierarchicalCache(sl, tl);
	}*/
	
	// [START Main]
	/**
	 * Based on command line argument, call processFile method to store the data
	 * at galileo server
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		// RANDOMIZED POLYGONS?
		randomOn = true;
		
		// CACHING MODE ON?
		cachingOn = false;
		
		// HOW MUCH PANNING?
		halfFrac = 0.05f;
		
		// REQUESTING OR CLEANING CACHE
		boolean reqMode = true;
		
		String parameters[] = new String[4];
		parameters[0] = "lattice-121.cs.colostate.edu";
		parameters[1] = "5634";
		parameters[2] = "city";
		
		int spRes = 6;
		int tRes = 3;
		
		if(args.length > 0) {
			parameters[0] = args[0];
			parameters[1] = args[1];
			parameters[2] = args[2];
			
			spRes = Integer.valueOf(args[3]);
			tRes = Integer.valueOf(args[4]);
			cachingOn = Boolean.valueOf(args[5]);
			
			group = Integer.valueOf(args[6]);
			each = Integer.valueOf(args[7]);
			
			
		}
		
		
		
		if (parameters.length == 0) {
			System.out.println("Usage: VisualizationQueryTest [galileo-hostname] [galileo-port-number] [query-size] [spatial-Resolution] [temporal-resolution]");
			System.exit(0);
		} else {
			try {
				List<GalileoConnector> gcs = new ArrayList<GalileoConnector>();
				
				GalileoConnector gc1 = new GalileoConnector(parameters[0], Integer.parseInt(parameters[1]));
				//System.out.println(parameters[0] + "," + Integer.parseInt(parameters[1]));
				
				if(reqMode) {
					
					for(int i=0; i<100; i++) {
						String host = "lattice-"+(i+121);
						GalileoConnector gc = new GalileoConnector(host, Integer.parseInt(parameters[1]));
						
						gcs.add(gc);
					}
					
					
					processRequest(gcs, spRes, tRes, parameters[2]);
				} else {
				
					if(cachingOn) {
						ClientMessageRouter messageRouter = new ClientMessageRouter();
						WipeCacheRequest wr = new WipeCacheRequest("namfs");
						messageRouter.sendMessage(gc1.server, EventPublisher.wrapEvent(wr));
						Thread.sleep(15*1000);
						System.out.println("CACHE WIPED OUT.");
					}
				}
				
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		System.out.println("Visualization Finished");
		System.exit(0);
	}
	// [END Main]
}
