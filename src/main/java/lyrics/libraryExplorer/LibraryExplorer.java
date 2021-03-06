/*
 * 	Mario Sangiorgio - mariosangiorgio AT gmail DOT com
 *
 *  This file is part of lyrics.
 * 
 *  lyrics is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  lyrics is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with lyrics.  If not, see <http://www.gnu.org/licenses/>.
 */

package lyrics.libraryExplorer;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Collection;
import java.util.Vector;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import lyrics.crawler.Crawler;
import lyrics.crawler.LyricsNotFoundException;
import lyrics.crawler.LyricsWikiaCrawler;
import lyrics.crawler.MetroLyricsCrawler;
import lyrics.crawler.SongLyricsCrawler;
import lyrics.utils.AlternateNames;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;


/**
 * Class that searches the library for audio files, reads their tags and finally
 * looks on the web to retrieve the lyrics
 * 
 * @author mariosangiorgio
 * 
 */
public class LibraryExplorer {
	private static Logger logger = Logger.getLogger("LibraryExplorer");
	private String path;
	private Vector<Crawler> crawlers = new Vector<Crawler>();
	private boolean override = false;
	private AlternateNames alternateNames = AlternateNames
			.getAlternateNames("/resources/AlternateNames");

	private Collection<String> badSentences = new Vector<String>();

	private Collection<OutputListener> outputListeners = new Vector<OutputListener>();

	/**
	 * Creates a new instance of the class specifying to lookup at the desired
	 * directory
	 * 
	 * @param path
	 *            the directory to explore
	 */
	public LibraryExplorer(String path) {
		this.path = path;
		crawlers.add(new LyricsWikiaCrawler());
		crawlers.add(new MetroLyricsCrawler());
		crawlers.add(new SongLyricsCrawler());
		// loadBadSentences(); TODO: load them properly from a configuration file
	}

	private void loadBadSentences() {
		URL path = getClass().getResource("/resources/badSentences");
		BufferedInputStream stream;
		try {
			stream = (BufferedInputStream) path.getContent();
			InputStreamReader streamReader = new InputStreamReader(stream);
			BufferedReader reader = new BufferedReader(streamReader);

			String line;
			while ((line = reader.readLine()) != null) {
				badSentences.add(line);
			}
			stream.close();
			streamReader.close();
			reader.close();

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Creates a new instance of the class, specifying the directory and the
	 * proxy settings
	 * 
	 * @param path
	 *            the directory to explore
	 * @param proxyHostname
	 *            the proxy hostname
	 * @param proxyPort
	 *            the proxy port
	 */
	public LibraryExplorer(String path, String proxyHostname, int proxyPort) {
		this(path);
		crawlers.add(new MetroLyricsCrawler(proxyHostname, proxyPort));
		crawlers.add(new SongLyricsCrawler(proxyHostname, proxyPort));
	}

	/**
	 * Creates a new instance of the class specifying to lookup at the desired
	 * directory and if it has to search for lyrics or just to fix the library
	 * 
	 * @param path
	 *            the directory to explore
	 * @param override
	 *            whether the application as to search for lyrics or fix the
	 *            library
	 */
	public LibraryExplorer(String path, boolean override) {
		this(path);
		this.override = override;
	}

	/**
	 * Creates a new instance of the class specifying to lookup at the desired
	 * directory, the proxy settings and if it has to search for lyrics or just
	 * to fix the library
	 * 
	 * @param path
	 *            the directory to explore
	 * @param proxyHostname
	 *            the proxy hostname
	 * @param proxyPort
	 *            the proxy port
	 * @param override
	 *            whether the application as to search for lyrics or fix the
	 *            library
	 */
	public LibraryExplorer(String path, String proxyHostname, int proxyPort,
			boolean override) {
		this(path, proxyHostname, proxyPort);
		this.override = override;
	}

	/**
	 * explores the directory specified in the constructor and performs the
	 * desired operations
	 */
	public void explore() {
		explore(path);
	}

	/**
	 * explores the directory specified in the parameter and performs the
	 * desired operations
	 * 
	 * @param path
	 *            the path where the method has to search audio files
	 */
	public void explore(String path) {
		File current = new File(path);
		if (current.isFile()) {
			try {
				AudioFile audioFile = AudioFileIO.read(current);
				Tag tag = audioFile.getTag();
				if (tag == null) {
					logger.warning("The file " + current
							+ " doesn't have any tag");
				}
				String lyrics = tag.getFirst(FieldKey.LYRICS);

				// This option is used when we want to override the lyrics
				// in order to fix their format
				if (override == true) {
					tag.setField(FieldKey.LYRICS, lyrics);
					audioFile.commit();
				}

				// We want to look for the lyrics in the web
				else {
					String artist = tag.getFirst(FieldKey.ARTIST);
					String title = tag.getFirst(FieldKey.TITLE);
					for (String artistName : alternateNames
							.getAlternateNameList(artist)) {

						if (lyricsAlreadyInTheFile(lyrics)) {
							break;
						}

						for (Crawler crawler : crawlers) {
							logger.info("Searching lyrics for " + title
									+ " by " + artistName + " with "
									+ crawler.getClass());
							try {
								lyrics = crawler.getLyrics(artistName, title);
								tag.setField(FieldKey.LYRICS, lyrics);
								audioFile.commit();
								notifySuccess(artist, title);
								break;
							} catch (LyricsNotFoundException ex) {
							}
						}
					}
					if (!lyricsAlreadyInTheFile(lyrics)) {
						// If the lyrics are not meaningful I drop them
						tag.setField(FieldKey.LYRICS, "");
						audioFile.commit();
						notifyFailure(artist, title);
					}
				}
			} catch (Exception e) {
				String message = "Error getting lyrics for " + current;
				notifyFailure(message);
				logger.severe(message);
			}
		} else {
			for (String f : current.list()) {
				if (!f.startsWith(".")) {
					explore(current.getAbsolutePath() + "/" + f);
				}
			}
		}
	}

	private boolean lyricsAlreadyInTheFile(String lyrics) {
		if (Pattern.matches("\\s*", lyrics))
			return false;
		for (String sentence : badSentences) {
			if (lyrics.contains(sentence)) {
				return false;
			}
		}
		return true;
	}

	private void notifyFailure(String message) {
		for (OutputListener listener : outputListeners) {
			listener.displaySuccessfulOperation(message);
		}
	}

	private void notifySuccess(String artist, String title) {
		for (OutputListener listener : outputListeners) {
			listener.displaySuccessfulOperation("Lyrics found for " + title
					+ " by " + artist);
		}
	}

	private void notifyFailure(String artist, String title) {
		for (OutputListener listener : outputListeners) {
			listener.displayUnsuccessfulOperation("Lyrics not found for "
					+ title + " by " + artist);
		}
	}

	/**
	 * Adds the specified object to the list of elements that has to be notified
	 * about what is going on
	 * 
	 * @param outputListener
	 *            the object to notify
	 */
	public void addOutputListener(OutputListener outputListener) {
		outputListeners.add(outputListener);
	}
}
