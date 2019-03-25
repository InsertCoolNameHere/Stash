package galileo.graph;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import galileo.dataset.Coordinates;
import galileo.fs.GeospatialFileSystem;
import galileo.serialization.ByteSerializable;
import galileo.serialization.SerializationException;
import galileo.serialization.SerializationInputStream;
import galileo.serialization.SerializationOutputStream;
import galileo.serialization.ByteSerializable.Deserialize;
import galileo.util.GeoHash;
import galileo.util.STRelatives;
import galileo.util.SpatialBorder;

public class CacheCell implements ByteSerializable{
	
	private SpatiotemporalHierarchicalCache cache;
	private String spatialInfo;
	private String temporalInfo;
	
	// MAINTAINED AS NON-ZERO NUMBERS
	private int spatialResolution;
	private int temporalResolution;
	
	private SummaryStatistics[] stats;
	
	private String spatialParent;
	private String temporalParent;
	
	private List<String> spatialChildren;
	private List<String> temporalChildren;
	
	private List<String> spatialNeighbors;
	private List<String> temporalNeighbors;

	private boolean hasParent;
	private boolean hasChildren;
	
	
	private float freshness;
	private long lastAccessed;
	private String lastEvent;
	
	private String cellKey;
	
	
	public String getCellKey() {
		return cellKey;
	}
	
	/**
	 * CREATING A NEW CACHE CELL
	 * UPDATE THE NEIGHBOR INFO AND OBJECT REFERENCES
	 * @param cache 
	 * @param stats
	 * @param numChildren
	 * @param numNeighbors
	 * @param numParents
	 * @param spatiotemporalInfo The spatial and temporal strings that define this cell block
	 * @param spatialResolution
	 * @param temporalResolution
	 * @param eventTime 
	 * @param eventId 
	 * @param eventId 
	 * @param freshnessMultiplier 
	 */
	
	public CacheCell(SpatiotemporalHierarchicalCache cache, SummaryStatistics[] stats, String spatiotemporalInfo,
			int spatialResolution, int temporalResolution, String eventId, long eventTime, int freshnessMultiplier) {
		
		this.cache = cache;
		this.stats = stats;
		this.spatialResolution = spatialResolution;
		this.temporalResolution = temporalResolution;
		
		this.cellKey = spatiotemporalInfo;
		String[] components = spatiotemporalInfo.split("\\$\\$");
		
		this.temporalInfo = components[0];
		this.spatialInfo = components[1];
		
		/* NEIGHBORS FOR THIS LEVEL */
		spatialNeighbors = GeoHash.getSpatialNeighboursSimplified(spatialInfo, GeospatialFileSystem.SPATIAL_SPREAD);
		temporalNeighbors = GeoHash.getTemporalNeighbors(temporalInfo, temporalResolution);
		
		/* PARENTS ONE LEVEL UP */
		if(spatialResolution > 1)
			spatialParent = GeoHash.getSpatialParent(spatialInfo, 1);
		else 
			spatialParent = null;
		
		if(temporalResolution > 1)
			temporalParent = GeoHash.getTemporalParent(temporalInfo, temporalResolution);
		else
			temporalParent = null;
		
		hasParent = true;
		
		if(spatialParent == null && temporalParent == null)
			hasParent = false;
		
		/* CHILDREN ONE LEVEL DOWN */
		if(spatialResolution < SpatiotemporalHierarchicalCache.totalSpatialLevels )
			spatialChildren = GeoHash.getSpatialChildren(spatialInfo);
		else 
			spatialChildren = null;
		
		if(temporalResolution < SpatiotemporalHierarchicalCache.totalTemporalLevels)
			temporalChildren = GeoHash.getTemporalChildren(temporalInfo, temporalResolution);
		else
			temporalChildren = null;
		
		hasChildren = true;
		
		if(spatialChildren == null && temporalChildren == null)
			hasChildren = false;
		
		this.freshness = 1*freshnessMultiplier;
		
		this.lastEvent = eventId;
		this.lastAccessed = eventTime;
		
	}

	public String getSpatialInfo() {
		return spatialInfo;
	}


	public void setSpatialInfo(String spatialInfo) {
		this.spatialInfo = spatialInfo;
	}


	public String getTemporalInfo() {
		return temporalInfo;
	}

	public void setTemporalInfo(String temporalInfo) {
		this.temporalInfo = temporalInfo;
	}
	
	public int getSpatialResolution() {
		return spatialResolution;
	}

	public void setSpatialResolution(int spatialResolution) {
		this.spatialResolution = spatialResolution;
	}

	public int getTemporalResolution() {
		return temporalResolution;
	}

	public void setTemporalResolution(int temporalResolution) {
		this.temporalResolution = temporalResolution;
	}

	public SummaryStatistics[] getStats() {
		return stats;
	}

	public void setStats(SummaryStatistics[] stats) {
		this.stats = stats;
	}

	public float getFreshness() {
		return freshness;
	}
	
	/**
	 * SEE IF THIS IS STALE FOR THE GUEST CACHE
	 * @author sapmitra
	 * @param currentTime
	 * @param hELPER_TIME 
	 * @return
	 */
	public boolean checkForFreshness(long currentTime, long HELPER_TIME) {
		
		boolean toRemove = false;
		long timeInt = currentTime - lastAccessed;
		
		if (timeInt >= HELPER_TIME)
			toRemove = true;
		
		return toRemove;
	}
	
	/**
	 * ADDED TIME DECAY
	 * @author sapmitra
	 * @param currentTime 
	 * @return
	 */
	public float getCorrectedFreshness(long currentTime) {
		
		double timeInt = currentTime - lastAccessed;
		
		double multiplier = java.lang.Math.exp(-1d * timeInt/1000);
		
		float updatedFrashness = (float)(freshness*multiplier);
		
		return updatedFrashness;
	}

	public void setFreshness(float freshness) {
		this.freshness = freshness;
	}

	public String getSpatialParent() {
		return spatialParent;
	}

	
	public void setSpatialParent(String spatialParent) {
		this.spatialParent = spatialParent;
	}

	public String getTemporalParent() {
		return temporalParent;
	}

	public void setTemporalParent(String temporalParent) {
		this.temporalParent = temporalParent;
	}

	public boolean isHasParent() {
		return hasParent;
	}

	public void setHasParent(boolean hasParent) {
		this.hasParent = hasParent;
	}

	public boolean isHasChildren() {
		return hasChildren;
	}

	public void setHasChildren(boolean hasChildren) {
		this.hasChildren = hasChildren;
	}

	
	
	public void incrementFreshness(float val, String eventId, long eventTime) {
		this.freshness += val;
		this.lastAccessed = eventTime;
		this.lastEvent = eventId;
	}
	
	
	public static void main(String arg[]) {
		Calendar cal = Calendar.getInstance(); // creates calendar
	    cal.setTime(new Date()); // sets calendar time/date
	    cal.add(Calendar.HOUR_OF_DAY, 280); // adds one hour
	    System.out.println(cal.getTime()); 
		
	}

	public List<String> getSpatialNeighbors() {
		return spatialNeighbors;
	}

	public void setSpatialNeighbors(List<String> spatialNeighbors) {
		this.spatialNeighbors = spatialNeighbors;
	}

	/**
	 * ONLY THE NEIGHBORS THAT NEED TO BE DISPERSED FRESHNESS
	 * @author sapmitra
	 * @param polygon
	 * @return
	 */
	public List<String> getRefinedSpatialNeighbors(List<Coordinates> polygon) {
		
		List<String> refinedNeighbors = new ArrayList<String>();
		
		for(String gh: spatialNeighbors) {
			boolean hasIntersection = GeoHash.checkIntersection(polygon, gh);
			
			if(!hasIntersection) {
				refinedNeighbors.add(gh);
			}
		}
		
		return refinedNeighbors;
	}
	
	public List<String> getTemporalNeighbors() {
		return temporalNeighbors;
	}
	
	
	public List<String> getRefinedTemporalNeighbors(long qt1, long qt2) {
		
		List<String> refinedTemporalNeighbors = new ArrayList<String>();
		
		for(String ts : temporalNeighbors) {
			try {
				boolean temporalIntersection = GeoHash.checkTemporalIntersection(ts, qt1, qt2);
				if(!temporalIntersection)
					refinedTemporalNeighbors.add(ts);
				
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			
		}
		return refinedTemporalNeighbors;
	}
	
	/**
	 * DISPERSING FRESHNESS VALUE TO A CELL'S RELATIVES
	 * 
	 * @author sapmitra
	 * @param polygon
	 * @param qt1
	 * @param qt2
	 * @param eventTime 
	 * @param eventId 
	 * @param freshnessMultiplier 
	 * @return
	 */
	public void freshenUpRelativesForCell(List<Coordinates> polygon, long qt1, long qt2, String eventId, long eventTime, int freshnessMultiplier) {
		
		freshenUpParents(eventId, eventTime, freshnessMultiplier);
		freshenUpChildren(eventId, eventTime, freshnessMultiplier);
		freshenUpNeighbors(polygon, qt1, qt2, eventId, eventTime, freshnessMultiplier);
		
		
	}
	

	public int getCellLevel() {
		
		return cache.getCacheLevel(spatialResolution, temporalResolution);
	}

	/**
	 * DISPERSE FRESHNESS AMONG NEIGHBORS
	 * @author sapmitra
	 * @param polygon
	 * @param qt1
	 * @param qt2
	 * @param eventTime 
	 * @param eventId 
	 */
	
	private void freshenUpNeighbors(List<Coordinates> polygon, long qt1, long qt2, String eventId, long eventTime, int freshnessMultiplier) {
		
		int cacheLevel = cache.getCacheLevel(spatialResolution, temporalResolution);
		// Only at the level of the current cell
		List<String> refinedSpatialNeighbors = getRefinedSpatialNeighbors(polygon);
		List<String> refinedTemporalNeighbors = getRefinedTemporalNeighbors(qt1, qt2);
		
		for(String s: refinedSpatialNeighbors) {
			for(String t: refinedTemporalNeighbors) {
				
				cache.disperseToCell(t+"$$"+s, cacheLevel, eventId, eventTime, freshnessMultiplier);
			}
		}
	}
	
	/**
	 * CHILDREN ARE CONSIDERED ONE SPATIAL/TEMPORAL LEVEL ABOVE, NOT BOTH
	 * DISPERSE FRESHNESS TO CHILDREN
	 * @author sapmitra
	 * @param eventId
	 * @param eventTime
	 * @param freshnessMultiplier 
	 */
	private void freshenUpChildren(String eventId, long eventTime, int freshnessMultiplier) {
		
		if(spatialChildren != null) {
			
			int cacheLevel = cache.getCacheLevel(spatialResolution+1, temporalResolution);
			
			for(String sc : spatialChildren) {
				
				cache.disperseToCell(temporalInfo+"$$"+sc, cacheLevel, eventId, eventTime, freshnessMultiplier);
				
			}
			
			
		} 
		
		if(temporalChildren != null) {
			
			int cacheLevel = cache.getCacheLevel(spatialResolution, temporalResolution+1);
			
			for(String tc: temporalChildren) {
				
				cache.disperseToCell(tc+"$$"+spatialInfo, cacheLevel, eventId, eventTime, freshnessMultiplier);
				
			}
			
		}
		
	}

	/**
	 * PARENTS ARE CONSIDERED ONE SPATIAL/TEMPORAL LEVEL ABOVE, NOT BOTH
	 * @author sapmitra
	 * @param eventTime 
	 * @param eventId 
	 * @param freshnessMultiplier 
	 * @return
	 */
	public void freshenUpParents(String eventId, long eventTime, int freshnessMultiplier) {
		
		String sp = getSpatialParent();
		String tp = getTemporalParent();
		
		if(sp != null) {
			
			// - 1 because we are loking for parents
			int cacheLevel = cache.getCacheLevel(spatialResolution-1, temporalResolution);
			
			cache.disperseToCell(temporalInfo+"$$"+sp, cacheLevel, eventId, eventTime, freshnessMultiplier);
			
		} 
		
		if(tp != null) {
			
			int cacheLevel = cache.getCacheLevel(spatialResolution, temporalResolution-1);
			cache.disperseToCell(tp+"$$"+spatialInfo, cacheLevel, eventId, eventTime, freshnessMultiplier);
			
		}
		
		/*if(sp != null && tp != null) {
			
			parentKeys.add(tp+"$$"+sp);
			
		}*/
		
	}
	
	/**
	 * Given a set of cache cells, returns the value, if any of an existing cell in the list
	 * matching a given key
	 * 
	 * @author sapmitra
	 * @param cells
	 * @param key
	 * @param currentTime 
	 * @return
	 */
	public static float getExistingCell(List<CacheCell> cells, String key, long currentTime) {
		
		for(CacheCell c : cells) {
			if(c.getCellKey().equals(key)) {
				return c.getCorrectedFreshness(currentTime);
			}
		}
		
		return 0;
		
	}
	
	
	

	public void setTemporalNeighbors(List<String> temporalNeighbors) {
		this.temporalNeighbors = temporalNeighbors;
	}

	public long getLastAccessed() {
		return lastAccessed;
	}

	public void setLastAccessed(long lastAccessed) {
		this.lastAccessed = lastAccessed;
	}

	public List<String> getSpatialChildren() {
		return spatialChildren;
	}

	public void setSpatialChildren(List<String> spatialChildren) {
		this.spatialChildren = spatialChildren;
	}

	public List<String> getTemporalChildren() {
		return temporalChildren;
	}

	public void setTemporalChildren(List<String> temporalChildren) {
		this.temporalChildren = temporalChildren;
	}

	public String getLastEvent() {
		return lastEvent;
	}

	public void setLastEvent(String lastEvent) {
		this.lastEvent = lastEvent;
	}

	@Override
	public void serialize(SerializationOutputStream out) throws IOException {
		
		out.writeString(cellKey);
		out.writeInt(spatialResolution);
		out.writeInt(temporalResolution);
		
		
		List<SummaryStatistics> summaries = new ArrayList<>();
		
		for(SummaryStatistics stat: stats) {
			summaries.add(stat);
		}
		out.writeSerializableCollection(summaries);
		
	}
	
	@Deserialize
	public CacheCell(SerializationInputStream in) throws IOException, SerializationException {
		
		this.cellKey = in.readString();
		this.spatialResolution = in.readInt();
		this.temporalResolution = in.readInt();
		
		List<SummaryStatistics> summaries = new ArrayList<>();
		in.readSerializableCollection(SummaryStatistics.class, summaries);
		
		stats = summaries.toArray(new SummaryStatistics[summaries.size()]);
		
		String[] components = cellKey.split("\\$\\$");
		
		this.temporalInfo = components[0];
		this.spatialInfo = components[1];
		
		/* NEIGHBORS FOR THIS LEVEL */
		spatialNeighbors = GeoHash.getSpatialNeighboursSimplified(spatialInfo, GeospatialFileSystem.SPATIAL_SPREAD);
		temporalNeighbors = GeoHash.getTemporalNeighbors(temporalInfo, temporalResolution);
		
		/* PARENTS ONE LEVEL UP */
		if(spatialResolution > 1)
			spatialParent = GeoHash.getSpatialParent(spatialInfo, 1);
		else 
			spatialParent = null;
		
		if(temporalResolution > 1)
			temporalParent = GeoHash.getTemporalParent(temporalInfo, temporalResolution);
		else
			temporalParent = null;
		
		hasParent = true;
		
		if(spatialParent == null && temporalParent == null)
			hasParent = false;
		
		/* CHILDREN ONE LEVEL DOWN */
		if(spatialResolution < SpatiotemporalHierarchicalCache.totalSpatialLevels )
			spatialChildren = GeoHash.getSpatialChildren(spatialInfo);
		else 
			spatialChildren = null;
		
		if(temporalResolution < SpatiotemporalHierarchicalCache.totalTemporalLevels)
			temporalChildren = GeoHash.getTemporalChildren(temporalInfo, temporalResolution);
		else
			temporalChildren = null;
		
		hasChildren = true;
		
		if(spatialChildren == null && temporalChildren == null)
			hasChildren = false;
		
		this.freshness = 0;
		
	}


}
