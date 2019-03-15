package galileo.graph;

import java.util.ArrayList;
import java.util.List;

public class CliqueContainer {
	
	private String geohashKey;
	private List<Integer> levels = null;
	private List<List<CacheCell>> cells = null;
	
	private String geohashAntipode;
	private int direction;
	
	private int totalCliqueSize = 0;
	
	public CliqueContainer(String key) {
		
		this.geohashKey = key;
		levels = new ArrayList<Integer>();
		cells = new ArrayList<List<CacheCell>>();
		
	}
	
	public void addCells(int level, List<CacheCell> cells) {

		this.levels.add(level);
		
		this.cells.add(cells);
		
		totalCliqueSize += cells.size();
		
		
	}
	
	
	
	
	
	public List<Integer> getLevels() {
		return levels;
	}
	public void setLevels(List<Integer> levels) {
		this.levels = levels;
	}
	public List<List<CacheCell>> getCells() {
		return cells;
	}
	public void setCells(List<List<CacheCell>> cells) {
		this.cells = cells;
	}

	public String getGeohashKey() {
		return geohashKey;
	}

	public void setGeohashKey(String geohashKey) {
		this.geohashKey = geohashKey;
	}

	public int getTotalCliqueSize() {
		return totalCliqueSize;
	}

	public void setTotalCliqueSize(int totalCliqueSize) {
		this.totalCliqueSize = totalCliqueSize;
	}

	public String getGeohashAntipode() {
		return geohashAntipode;
	}

	public void setGeohashAntipode(String geohashAntipode) {
		this.geohashAntipode = geohashAntipode;
	}

	public int getDirection() {
		return direction;
	}

	public void setDirection(int direction) {
		this.direction = direction;
	}
	
	

}
