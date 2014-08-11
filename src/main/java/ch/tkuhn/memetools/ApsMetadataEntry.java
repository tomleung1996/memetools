package ch.tkuhn.memetools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

public class ApsMetadataEntry {

	private String id;
	private String date;
	private Map<String,String> title;
	@SerializedName("abstract")
	private Map<String,String> abstractText;

	public static ApsMetadataEntry load(File file) throws FileNotFoundException {
		BufferedReader br = new BufferedReader(new FileReader(file));
		return new Gson().fromJson(br, ApsMetadataEntry.class);
	}

	public String getId() {
		return id;
	}

	public String getDate() {
		return date;
	}

	public String getNormalizedTitle() {
		if (title == null || !title.containsKey("value")) return null;
		String n = title.get("value");
		if (title.containsKey("format") && title.get("format").startsWith("html")) {
			n = preprocessHtml(n);
		}
		return MemeUtils.normalize(n);
	}

	public String getNormalizedAbstract() {
		if (abstractText == null || !abstractText.containsKey("value")) return null;
		String n = abstractText.get("value");
		if (abstractText.containsKey("format") && abstractText.get("format").startsWith("html")) {
			n = preprocessHtml(n);
		}
		return MemeUtils.normalize(n);
	}

	public String preprocessHtml(String htmlText) {
		htmlText = htmlText.replaceAll("</?(p|br|b|i|span|mi|mo|mn|math) ?.*?/?>", "");
		htmlText = htmlText.replaceAll("(</?[a-z]+) .*?(/?>)", "$1$2");
		return htmlText;
	}

}