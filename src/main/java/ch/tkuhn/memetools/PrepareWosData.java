package ch.tkuhn.memetools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

public class PrepareWosData {

	@Parameter(names = "-v", description = "Write detailed log")
	private boolean verbose = false;

	@Parameter(names = "-rth", description = "Threshold on references (discard publications with less references)")
	private int rth = 0;

	@Parameter(names = "-cth", description = "Threshold on citations (discard publications with less citations)")
	private int cth = 0;

	@Parameter(names = "-d", description = "The directory to read the raw data from")
	private File rawWosDataDir;

	private File logFile;

	public static final void main(String[] args) {
		PrepareWosData obj = new PrepareWosData();
		JCommander jc = new JCommander(obj);
		try {
			jc.parse(args);
		} catch (ParameterException ex) {
			jc.usage();
			System.exit(1);
		}
		obj.run();
	}

	private static String wosFolder = "wos";

	private Map<String,String> titles;
	private Map<String,String> years;
	private Map<String,String> references;

	private Set<FileVisitOption> walkFileTreeOptions;

	public PrepareWosData() {
	}

	public void run() {
		init();
		try {
			readData();
			writeDataFile();
			writeGmlFile();
		} catch (Throwable th) {
			log(th);
			System.exit(1);
		}
		log("Finished");
	}

	private void init() {
		logFile = new File(MemeUtils.getLogDir(), "prepare-wos.log");
		log("==========");
		log("Starting...");

		titles = new HashMap<String,String>();
		years = new HashMap<String,String>();
		references = new HashMap<String,String>();

		walkFileTreeOptions = new HashSet<FileVisitOption>();
		walkFileTreeOptions.add(FileVisitOption.FOLLOW_LINKS);
	}

	private void readData() throws IOException {
		if (rawWosDataDir == null) {
			rawWosDataDir = new File(MemeUtils.getRawDataDir(), wosFolder);
		}
		log("Reading files from " + rawWosDataDir + " ...");
		Files.walkFileTree(rawWosDataDir.toPath(), walkFileTreeOptions, Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
				if (path.toString().endsWith(".txt")) {
					readData(path);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		log("Number of documents: " + titles.size());
	}

	private void readData(Path path) throws IOException {
		log("Reading file to collect IDs: " + path);
		BufferedReader reader = new BufferedReader(new FileReader(path.toFile()), 64*1024);
		int errors = 0;
		String line;
		while ((line = reader.readLine()) != null) {
			WosEntry entry = new WosEntry(line);
			if (!entry.isValid()) {
				errors++;
				continue;
			}
			if (entry.refCount < rth) continue; 
			if (entry.citCount < cth) continue; 
			titles.put(entry.id, MemeUtils.normalize(entry.title));
			years.put(entry.id, entry.year);
			references.put(entry.id, entry.ref);
		}
		reader.close();
		log("Number of errors: " + errors);
	}

	private void writeDataFile() throws IOException {
		log("Writing data file...");
		String filename = "wos-T";
		if (cth > 0) filename += "-c" + cth;
		if (rth > 0) filename += "-r" + rth;
		File file = new File(MemeUtils.getPreparedDataDir(), filename + ".txt");
		BufferedWriter wT = new BufferedWriter(new FileWriter(file));
		for (String doi1 : titles.keySet()) {
			String text = titles.get(doi1);
			String year = years.get(doi1);
			DataEntry e = new DataEntry(doi1, year, text);
			String refs = references.get(doi1);
			while (!refs.isEmpty()) {
				String doi2 = refs.substring(0, 9);
				refs = refs.substring(9);
				if (titles.containsKey(doi2)) {
					e.addCitedText(titles.get(doi2));
				}
			}
			wT.write(e.getLine() + "\n");
		}
		wT.close();
	}

	private void writeGmlFile() throws IOException {
		log("Writing GML file...");
		String filename = "wos";
		if (cth > 0) filename += "-c" + cth;
		if (rth > 0) filename += "-r" + rth;
		File file = new File(MemeUtils.getPreparedDataDir(), filename + ".gml");
		BufferedWriter w = new BufferedWriter(new FileWriter(file));
		w.write("graph [\n");
		w.write("directed 1\n");
		for (String doi : titles.keySet()) {
			String year = years.get(doi);
			String text = titles.get(doi);
			text = " " + text + " ";
			w.write("node [\n");
			w.write("id \"" + doi + "\"\n");
			w.write("year \"" + year + "\"\n");
			// TODO Make this general:
			if (text.contains(" quantum ")) w.write("memeQuantum \"y\"\n");
			if (text.contains(" traffic ")) w.write("memeTraffic \"y\"\n");
			if (text.contains(" black hole ")) w.write("memeBlackHole \"y\"\n");
			if (text.contains(" graphene ")) w.write("memeGraphene \"y\"\n");
			w.write("]\n");
		}
		for (String doi1 : references.keySet()) {
			String refs = references.get(doi1);
			while (!refs.isEmpty()) {
				String doi2 = refs.substring(0, 9);
				refs = refs.substring(9);
				if (titles.containsKey(doi2)) {
					w.write("edge [\n");
					w.write("source \"" + doi1 + "\"\n");
					w.write("target \"" + doi2 + "\"\n");
					w.write("]\n");
				}
			}
		}
		w.write("]\n");
		w.close();
	}

	private void log(Object obj) {
		MemeUtils.log(logFile, obj);
	}

	private void logDetail(Object obj) {
		if (verbose) log(obj);
	}

	
	// Data format: semicolon-delimited with the following columns:
	//
    // 0 t9 (9 last digits)
    // 1 year
    // 2 documentType (one letter)
    // 3 doi (optional)
    // 4 subject (two letters, optional)
    // 5 iso journal (optional)
    // 6 volume
    // 7 issue
    // 8 pages
    // 9 title
    // 10 noOfAuthors
    // 11.. authors
    // 11+noOfAuthors noOfJournals
    // 12+noOfAuthors... journals (other journal labels)
    // 12+noOfAuthors+noOfJournals summary (optional)
    // 13+noOfAuthors+noOfJournals references (non-delimited t9)
    // 14+noOfAuthors+noOfJournals citations (non-delimited t9)

	private class WosEntry {

		private boolean valid = false;

		String id;
		String title;
		String year;
		String ref;
		String cit;
		int refCount;
		int citCount;

		WosEntry(String line) {
			String[] parts = line.split(";", -1);
			if (parts.length < 15) {
				logDetail("Invalid line: " + line);
				return;
			}
			id = parts[0];
			if (!id.matches("[0-9]{9}")) {
				logDetail("Invalid ID: " + id);
				return;
			}
			year = parts[1];
			if (!year.matches("[0-9]{4}")) {
				logDetail("Invalid year: " + year);
				return;
			}
			title = parts[9];
			if (title.isEmpty()) {
				logDetail("Empty title for publication: " + id);
				return;
			}
			ref = parts[parts.length-2];
			if (!ref.matches("([0-9]{9})*")) {
				logDetail("Invalid references: " + ref);
				return;
			}
			refCount = ref.length() / 9;
			cit = parts[parts.length-1];
			if (!cit.matches("([0-9]{9})*")) {
				logDetail("Invalid citations: " + cit);
				return;
			}
			citCount = cit.length() / 9;
			valid = true;
		}

		boolean isValid() {
			return valid;
		}

	}

}
