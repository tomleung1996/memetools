package ch.tkuhn.memetools;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.supercsv.io.CsvListReader;

import ch.tkuhn.memetools.PrepareWosData.WosEntry;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

public class RenderGraph {

	@Parameter(description = "input-file", required = true)
	private List<String> parameters = new ArrayList<String>();

	private File inputFile;

	@Parameter(names = "-o", description = "Output file")
	private File outputFile;

	@Parameter(names = "-d", description = "The directory to read the raw data from")
	private File rawWosDataDir;

	@Parameter(names = "-s", description = "Size of output bitmap")
	private int size = 10000;

	@Parameter(names = "-sc", description = "Scaling factor")
	private float scale = 0.5f;

	@Parameter(names = "-ds", description = "Size of dots")
	private int dotSize = 4;

	@Parameter(names = "-v", description = "Write detailed log")
	private boolean verbose = false;

	private float edgeAlpha = 0.001f;

	private File logFile;

	public static final void main(String[] args) {
		RenderGraph obj = new RenderGraph();
		JCommander jc = new JCommander(obj);
		try {
			jc.parse(args);
		} catch (ParameterException ex) {
			jc.usage();
			System.exit(1);
		}
		if (obj.parameters.size() != 1) {
			System.err.println("ERROR: Exactly one main argument is needed");
			jc.usage();
			System.exit(1);
		}
		obj.inputFile = new File(obj.parameters.get(0));
		obj.run();
	}

	private static String wosFolder = "wos";

	private float[] pointsX;
	private float[] pointsY;

	private float[][] edgeMap;
	private BufferedImage image;
	private Graphics graphics;

	private Set<FileVisitOption> walkFileTreeOptions;

	public RenderGraph() {
	}

	public void run() {
		try {
			init();
			readNodes();
			drawEdges();
			drawNodes();
			writeImage();
		} catch (Throwable th) {
			log(th);
			System.exit(1);
		}
		log("Finished");
	}

	private void init() throws IOException {
		logFile = new File(MemeUtils.getLogDir(), getOutputFileName() + ".log");
		log("==========");
		log("Starting...");

		if (outputFile == null) {
			outputFile = new File(MemeUtils.getOutputDataDir(), getOutputFileName() + ".png");
		}
		if (rawWosDataDir == null) {
			rawWosDataDir = new File(MemeUtils.getRawDataDir(), wosFolder);
		}

		pointsX = new float[150000000];
		pointsY = new float[150000000];

		edgeMap = new float[size][size];
		image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		graphics = image.getGraphics();

		walkFileTreeOptions = new HashSet<FileVisitOption>();
		walkFileTreeOptions.add(FileVisitOption.FOLLOW_LINKS);
	}

	private void readNodes() throws IOException {
		log("Processing nodes from input file: " + inputFile);
		BufferedReader r = new BufferedReader(new FileReader(inputFile), 64*1024);
		CsvListReader csvReader = new CsvListReader(r, MemeUtils.getCsvPreference());
		int progress = 0;
		List<String> line;
		while ((line = csvReader.read()) != null) {
			logProgress(progress);
			progress++;
			int id = Integer.parseInt(line.get(0));
			pointsX[id] = Float.parseFloat(line.get(1));
			pointsY[id] = Float.parseFloat(line.get(2));
		}
		csvReader.close();
	}

	private void drawNodes() {
		log("Drawing nodes...");
		graphics.setColor(new Color(0, 0, 255, 16));
		int progress = 0;
		for (int i = 0 ; i < 150000000 ; i++) {
			logProgress(progress);
			progress++;
			float x = pointsX[i];
			if (pointsX[i] == 0) continue;
			float y = pointsY[i];
			graphics.fillOval((int) (x*scale - dotSize/2.0), (int) (size - y*scale - dotSize/2.0), dotSize, dotSize);
		}
	}

	private void drawEdges() throws IOException {
		log("Drawing edges from " + rawWosDataDir + " ...");
		Files.walkFileTree(rawWosDataDir.toPath(), walkFileTreeOptions, Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
				if (path.toString().endsWith(".txt")) {
					processEdgesFromFile(path);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		for (int x = 0 ; x < size ; x++) {
			for (int y = 0 ; y < size ; y++) {
				int p = 0;
				float v = edgeMap[x][y];
				if (v > 0) p = (int) (edgeMap[x][y] * 123 + 1);
				image.setRGB(x, y, 0xffffff - p * 0x010101);
			}
		}
	}

	private void processEdgesFromFile(Path path) throws IOException {
		log("Reading file to collect edges: " + path);
		BufferedReader reader = new BufferedReader(new FileReader(path.toFile()), 64*1024);
		int errors = 0;
		String line;
		while ((line = reader.readLine()) != null) {
			WosEntry entry;
			if (verbose) {
				entry = new WosEntry(line, logFile);
			} else {
				entry = new WosEntry(line);
			}
			if (!entry.isValid()) {
				errors++;
				continue;
			}
			int id1 = entry.getIdInt();
			if (pointsX[id1] == 0) continue;
			String neighborIds = entry.getRef();
			while (!neighborIds.isEmpty()) {
				String nId = neighborIds.substring(0, 9);
				int id2 = Integer.parseInt(nId);
				neighborIds = neighborIds.substring(9);
				if (pointsX[id2] == 0) continue;
				// draw line
				int x1 = (int) (pointsX[id1]*scale);
				int y1 = (int) (size - pointsY[id1]*scale);
				int x2 = (int) (pointsX[id2]*scale);
				int y2 = (int) (size - pointsY[id2]*scale);
				if (Math.abs(x2 - x1) > Math.abs(y2 - y1)) {
					if (x1 > x2) {
						int tx1 = x1;
						x1 = x2;
						x2 = tx1;
						int ty1 = y1;
						y1 = y2;
						y2 = ty1;
					}
					for (int x = x1 ; x <= x2 ; x++) {
						drawEdgePixel(x, (int) ( ((float) (x - x1) / (x2 - x1)) * (y2 - y1) + y1 ) );
					}
				} else {
					if (y1 > y2) {
						int tx1 = x1;
						x1 = x2;
						x2 = tx1;
						int ty1 = y1;
						y1 = y2;
						y2 = ty1;
					}
					for (int y = y1 ; y <= y2 ; y++) {
						drawEdgePixel((int) ( ((float) (y - y1) / (y2 - y1)) * (x2 - x1) + x1 ), y);
					}
				}
			}
		}
		reader.close();
		log("Number of errors: " + errors);
	}

	private void drawEdgePixel(int x, int y) {
		float v = edgeMap[x][y];
		edgeMap[x][y] = (float) (v + edgeAlpha - v * edgeAlpha);
	}

	private void writeImage() throws IOException {
		log("Writing image to" + outputFile + " ...");
		ImageIO.write(image, "png", outputFile);
	}

	private String getOutputFileName() {
		return "im-" + inputFile.getName().replaceAll("\\..*$", "");
	}

	private void log(Object obj) {
		MemeUtils.log(logFile, obj);
	}

	private void logProgress(int p) {
		if (p % 1000000 == 0) log(p + "...");
	}

}
