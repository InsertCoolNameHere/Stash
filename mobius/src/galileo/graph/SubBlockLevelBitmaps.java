package galileo.graph;

import java.util.Calendar;

import org.joda.time.DateTime;

import galileo.bmp.Bitmap;
import galileo.comm.TemporalType;
import galileo.dataset.TemporalProperties;
import galileo.dht.hash.TemporalHash;
import galileo.fs.GeospatialFileSystem;
import galileo.util.GeoHash;

public class SubBlockLevelBitmaps {
	
	// Actual Number of sub-levels. 
	
	private int spatialFSLevel;
	private int temporalFSLevel;
	
	// Number of sub block spatial levels
	private int spatialLevels;
	// Number of sub block level temporalLevels
	private int temporalLevels;
	private Bitmap[] spatioTemporalBitmaps;
	
	public SubBlockLevelBitmaps(int spatialSubLevels, int temporalSubLevels, int geohashPrecision, int temporalFSLevel) {
		
		this.spatialLevels = spatialSubLevels;
		this.temporalLevels = temporalSubLevels;
		this.spatialFSLevel = geohashPrecision;
		this.temporalFSLevel = temporalFSLevel;
		this.spatioTemporalBitmaps = new Bitmap[(spatialLevels+1)*(temporalLevels+1)];
		
	}
	
	/**
	 * RETURNS THE BITMAP FOR A REQUESTED SPATIOTEMPORAL LEVEL
	 * @author sapmitra
	 * @param spatialLevel
	 * @param temporalLevel
	 * @return
	 */
	public Bitmap getBitMapForParticularLevel(int spatialLevel, int temporalLevel) {
		int indx = getMapIndex(spatialLevel, temporalLevel);
		
		return spatioTemporalBitmaps[indx];
		
	}
	
	/**
	 * Returns the index for which particular bitmap to be used
	 * @author sapmitra
	 * @param spatialLevel - actual spatial level, not relative
	 * @param temporalLevel - actual temporal level, not relative
	 * @return
	 */
	
	private int getMapIndex(int spatialLevel, int temporalLevel) {
		int spLvl = spatialLevel - spatialFSLevel;
		int tLvl = temporalLevel - temporalFSLevel;
		
		int indx = (spLvl)*spatialLevels + tLvl;
		return indx;
	}
	
	/**
	 * 
	 * @author sapmitra
	 * @param records
	 * @param temporalPosn 
	 * @param spatialPosn2 
	 * @param spatialPosn1 
	 * @param startDate 
	 * @param removeLength 
	 */
	public void populateTemporaryBitmapUsingRecords (String[] records, int spatialPosn1, int spatialPosn2, int temporalPosn, int removeLength, DateTime startDate) {
		
		Bitmap[] temporaryBitmaps  = new Bitmap[(spatialLevels+1)*(temporalLevels+1)];
		
		for(String record: records) {
			
			String[] fields = record.split(",");
			
			// Highest resolution geohash allowed by visualization application
			String geoHash = GeoHash.encode(GeospatialFileSystem.parseFloat(fields[spatialPosn1]),
					GeospatialFileSystem.parseFloat(fields[spatialPosn2]), spatialFSLevel+spatialLevels);
			
			long timestamp = GeospatialFileSystem.reformatDatetime(fields[temporalPosn]);
			
			String choppedGeohash = geoHash.substring(removeLength);
			
			populateBitmapUsingRecord(startDate, timestamp, choppedGeohash, temporaryBitmaps);
			
			
		}
		
		for(int i = 0; i< temporaryBitmaps.length; i++) {
			if(spatioTemporalBitmaps[i] == null) {
				spatioTemporalBitmaps[i] = temporaryBitmaps[i];
			} else {
				spatioTemporalBitmaps[i] = spatioTemporalBitmaps[i].or(temporaryBitmaps[i]);
			}
		}
	}
	
	/**
	 * 
	 * @author sapmitra
	 * @param startTime
	 * @param recordTimestamp
	 * @param choppedGeohash The remainder portion of the geohash below block level
	 */
	public void populateBitmapUsingRecord(DateTime startTime, long recordTimestamp, String choppedGeohash, Bitmap[] temporaryBitmaps) {
		
		
		// Update each bitmap corresponding to a record
		for(int i=0; i<= spatialLevels; i++) {
			
			for(int j=0; j <= temporalLevels; j++) {
				
				if(i==0 && j == 0) {
					continue;
				}
				
				int currentSpatialLevel = spatialFSLevel+i;
				int currentTemporalLevel = temporalFSLevel+j;
				
				int indx = getMapIndex(currentSpatialLevel, currentTemporalLevel);
				
				long spatialIndex = 0;
				
				// if we do not go down the spatial resolution but only in temporal resolution
				if(i > 0) {
					
					String partialGeohash = choppedGeohash.substring(0, i+1);
					
					// returns a number between 0 and 31 for single character
					spatialIndex = GeoHash.hashToLong(partialGeohash);
					
				}
				
				long temporalIndex = 0;
				
				if(j > 0)
					temporalIndex = TemporalType.getTemporalIndex(startTime, recordTimestamp, currentTemporalLevel);
				
				Bitmap bm = temporaryBitmaps[indx];
				
				if(bm == null) {
					bm = new Bitmap();
					temporaryBitmaps[indx] = bm;
				}
				
				int spatialSize = (int)java.lang.Math.pow(32, i);
				
				int spatiotemporalIndex = (int)(temporalIndex*spatialSize + spatialIndex);
				
				
				bm.set(spatiotemporalIndex);
				
			}
		}
		
	}
	
	/**
	 * Given a bitmap bit position and resolution, this returns the key for a cell that that bit represents
	 * Cell key is in a time$$space format
	 * @author sapmitra
	 * @param bitmapIndex
	 * @param sresolution - actual, not relative
	 * @param tresolution - actual, not relative. This is the temporal resolution of the cells being fetched, not the block.
	 * @param sFSLevel - spatial resolution of a block
	 * @param tFSLevels
	 * @return
	 */
	public static String getKeyFromBitmapIndex(int bitmapIndex, int sresolution, int tresolution, int sFSLevel, long startTimestamp) {
		
		int spatialSize = (int)java.lang.Math.pow(32, sresolution - sFSLevel);
		
		int spatialIndex = bitmapIndex%spatialSize;
		
		int temporalIndex = bitmapIndex/spatialSize;
		
		//GeoHash.hashToLong(hash);
		String geohashString = GeoHash.fromLongToString(spatialIndex, sresolution);
		
		long newTimestamp = TemporalType.addTime(startTimestamp, temporalIndex, tresolution);
		String temporalString = getTemporalString(newTimestamp, tresolution);
		
		return temporalString+"$$"+geohashString;
	}
	
	/**
	 * Create a temporal string with xx for missing fields
	 */
	private static String getTemporalString(long timestamp, int temporalLevel) {
		
		Calendar c = Calendar.getInstance();
		c.setTimeZone(TemporalHash.TIMEZONE);
		c.setTimeInMillis(timestamp);
		int hour = c.get(Calendar.HOUR_OF_DAY);
		int day = c.get(Calendar.DAY_OF_MONTH);
		int month = c.get(Calendar.MONTH) + 1;
		int year = c.get(Calendar.YEAR);
		
		switch (temporalLevel) {
		case 4:
			return String.format("%d-%d-%d-%d", year, month, day, hour);
		case 3:
			return String.format("%d-%d-%d-xx", year, month, day);
		case 2:
			return String.format("%d-%d-xx-xx", year, month);
		case 1:
			return String.format("%d-xx-xx-xx", year);
		}
		return String.format("%d-%d-%d-xx", year, month, day);
	}
	
	public static void main(String arg[]) {
		
		//long hashToLong = GeoHash.hashToLong("0");
		//System.out.println("0 "+hashToLong+GeoHash.fromLongToString(hashToLong));
		
		long hashToLong = GeoHash.hashToLong("cx0");
		System.out.println("cx "+hashToLong+" "+GeoHash.fromLongToString(hashToLong,3));
		
		
		//hashToLong = GeoHash.hashToLong("f");
		//System.out.println("f "+hashToLong+GeoHash.fromLongToString(hashToLong));
		//hashToLong = GeoHash.hashToLong("g");
		//System.out.println("g "+hashToLong+GeoHash.fromLongToString(hashToLong));
		//hashToLong = GeoHash.hashToLong("u");
		//System.out.println("u "+hashToLong+GeoHash.fromLongToString(hashToLong));
		//hashToLong = GeoHash.hashToLong("z");
		//System.out.println("z "+hashToLong+GeoHash.fromLongToString(hashToLong));
		
	}

}