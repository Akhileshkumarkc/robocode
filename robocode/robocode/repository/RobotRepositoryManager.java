/*******************************************************************************
 * Copyright (c) 2001, 2008 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://robocode.sourceforge.net/license/cpl-v10.html
 *
 * Contributors:
 *     Mathew A. Nelson
 *     - Initial API and implementation
 *     Flemming N. Larsen
 *     - Replaced FileSpecificationVector with plain Vector
 *     - Updated to use methods from WindowUtil, FileTypeFilter, FileUtil, Logger,
 *       which replaces methods that have been (re)moved from the Utils class
 *     - Changed to use FileUtil.getRobotsDir()
 *     - Replaced multiple catch'es with a single catch in
 *       getSpecificationsInDirectory()
 *     - Minor optimizations
 *     - Added missing close() on FileInputStream
 *     - Changed updateRobotDatabase() to take the new JuniorRobot class into
 *       account
 *     - Bugfix: Ignore robots that reside in the .robotcache dir when the
 *       robot.database is updated by updateRobotDatabase()
 *     Robert D. Maupin
 *     - Replaced old collection types like Vector and Hashtable with
 *       synchronized List and HashMap
 *     - Changed so that the robot repository only adds .jar files from the root
 *       of the robots folder and not from sub folders of the robots folder
 *     Pavel Savara
 *     - Re-work of robot interfaces
 *     - detection of type of robot by overriden methods
 *******************************************************************************/
package robocode.repository;


import robocode.dialog.WindowUtil;
import robocode.io.FileTypeFilter;
import robocode.io.FileUtil;
import net.sf.robocode.io.Logger;
import static net.sf.robocode.io.Logger.logError;
import static net.sf.robocode.io.Logger.logMessage;
import robocode.manager.IRepositoryManager;
import robocode.manager.RobocodeManager;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;


/**
 * @author Mathew A. Nelson (original)
 * @author Flemming N. Larsen (contributor)
 * @author Robert D. Maupin (contributor)
 * @author Pavel Savara (contributor)
 */
public class RobotRepositoryManager implements IRepositoryManager {
	private FileSpecificationDatabase robotDatabase;

	private File robotsDirectory;
	private File robotCache;

	private Repository repository;
	private final RobocodeManager manager;

	private final List<FileSpecification> updatedJarList = Collections.synchronizedList(
			new ArrayList<FileSpecification>());
	private boolean write;

	public RobotRepositoryManager(RobocodeManager manager) {
		this.manager = manager;
	}

	public File getRobotCache() {
		if (robotCache == null) {
			File oldRobotCache = new File(getRobotsDirectory(), "robotcache");
			File newRobotCache = new File(getRobotsDirectory(), ".robotcache");

			if (oldRobotCache.exists()) {
				if (!oldRobotCache.renameTo(newRobotCache)){
					Logger.logError("Can't move " + newRobotCache.toString());
				}
			}

			robotCache = newRobotCache;

			if (!robotCache.exists()) {
				if (!robotCache.mkdirs()){
					Logger.logError("Can't create " + robotCache.toString());
				}
				File readme = new File(robotCache, "README");

				try {
					PrintStream out = new PrintStream(new FileOutputStream(readme));

					out.println("WARNING!");
					out.println("Do not edit files in this directory.");
					out.println("Any changes you make here may be lost.");
					out.println("If you want to make changes to these robots,");
					out.println("then copy the files into your robots directory");
					out.println("and make the changes there.");
					out.close();
				} catch (IOException ignored) {}
			}

		}
		return robotCache;
	}

	private FileSpecificationDatabase getRobotDatabase() {
		if (robotDatabase == null) {
			WindowUtil.setStatus("Reading robot database");
			robotDatabase = new FileSpecificationDatabase();
			try {
				robotDatabase.load(new File(getRobotsDirectory(), "robot.database"));
			} catch (FileNotFoundException e) {
				logMessage("Building robot database.");
			} catch (IOException e) {
				logMessage("Rebuilding robot database.");
			} catch (ClassNotFoundException e) {
				logMessage("Rebuilding robot database.");
			}
		}
		return robotDatabase;
	}

	public void loadRobotRepository() {
		// Don't reload the repository
		// If we want to do that, set repository to null by calling clearRobotList().
		if (repository != null) {
			return;
		}

		WindowUtil.setStatus("Refreshing robot database");

		updatedJarList.clear();
		this.write = false;

		// Future...
		// Remove any deleted jars from robotcache

		repository = new Repository();

		// Clean up cache -- delete nonexistent jar directories
		cleanupCache();
		WindowUtil.setStatus("Cleaning up robot database");
		cleanupDatabase();

		String externalRobotsPath = manager.getProperties().getOptionsDevelopmentPath(); {
			StringTokenizer tokenizer = new StringTokenizer(externalRobotsPath, File.pathSeparator);

			while (tokenizer.hasMoreTokens()) {
				String tok = tokenizer.nextToken();
				File f = new File(tok);

				if (!f.equals(getRobotsDirectory()) && !f.equals(getRobotCache())
						&& !f.equals(getRobotsDirectory().getParentFile())) {
					getSpecificationsInDirectory(f, f, "", true);
				}
			}
		}
		updatedJarList.clear();

		File f = getRobotsDirectory();

		WindowUtil.setStatus("Reading: " + f.getName());
		if (f.exists() && f.isDirectory()) { // it better be!
			getSpecificationsInDirectory(f, f, "", true);
		}

		// This loop should not be changed to an for-each loop as the updated jar list
		// gets updated (jars are added) by the methods called in this loop, which can
		// cause a ConcurrentModificationException!
		// noinspection ForLoopReplaceableByForEach
		for (int i = 0; i < updatedJarList.size(); i++) {
			JarSpecification updatedJar = (JarSpecification) updatedJarList.get(i);

			updatedJar.processJar(getRobotCache(), getRobotsDirectory());
			getRobotDatabase().put(updatedJar.getFilePath(), updatedJar);
			updateRobotDatabase(updatedJar);
			write = true;
		}
		updatedJarList.clear();

		f = getRobotCache();
		WindowUtil.setStatus("Reading: " + getRobotCache());
		if (f.exists() && f.isDirectory()) { // it better be!
			getSpecificationsInDirectory(f, f, "", false);
		}

		List<FileSpecification> fileSpecificationList = getRobotDatabase().getFileSpecifications();

		if (write) {
			WindowUtil.setStatus("Saving robot database");
			saveRobotDatabase();
		}

		WindowUtil.setStatus("Adding robots to repository");

		for (FileSpecification fs : fileSpecificationList) {
			if (fs instanceof RobotFileSpecification || fs instanceof TeamSpecification) {
				repository.add(fs);
			}
		}

		WindowUtil.setStatus("Sorting repository");
		repository.sortRobotSpecifications();
		WindowUtil.setStatus("");
	}

	private void cleanupCache() {
		File dir = getRobotCache();
		File files[] = dir.listFiles();

		if (files != null) {
			for (File file : files) {
				if (file.isDirectory() && file.getName().lastIndexOf(".jar_") == file.getName().length() - 5
						|| file.isDirectory() && file.getName().lastIndexOf(".zip_") == file.getName().length() - 5
						|| file.isDirectory() && file.getName().lastIndexOf(".jar") == file.getName().length() - 4) {
					File f = new File(getRobotsDirectory(), file.getName().substring(0, file.getName().length() - 1));

					// startsWith robocode added in 0.99.5 to fix bug with people
					// downloading robocode-setup.jar to the robots dir
					if (f.exists() && !f.getName().startsWith("robocode")) {
						continue;
					}
					WindowUtil.setStatus("Cleaning up cache: Removing " + file);
					FileUtil.deleteDir(file);
				}
			}
		}
	}

	private void cleanupDatabase() {
		List<File> externalDirectories = new ArrayList<File>();
		String externalPath = manager.getProperties().getOptionsDevelopmentPath();
		StringTokenizer tokenizer = new StringTokenizer(externalPath, File.pathSeparator);

		while (tokenizer.hasMoreTokens()) {
			File f = new File(tokenizer.nextToken());

			externalDirectories.add(f);
		}

		List<FileSpecification> fileSpecificationList = getRobotDatabase().getFileSpecifications();

		for (FileSpecification fs : fileSpecificationList) {
			if (fs.exists()) {
				File rootDir = fs.getRootDir();

				if (rootDir == null) {
					logError("Warning, null root directory: " + fs.getFilePath());
					continue;
				}

				if (!fs.isDevelopmentVersion()) {
					continue;
				}
				if (rootDir.equals(getRobotsDirectory())) {
					continue;
				}
				if (externalDirectories.contains(rootDir)) {
					continue;
				}

				// This one is from the developmentPath; make sure that path still exists.
				getRobotDatabase().remove(fs.getFilePath());
				write = true;
			} else {
				getRobotDatabase().remove(fs.getFilePath());
				write = true;
			}
		}
	}

	public File getRobotsDirectory() {
		if (robotsDirectory == null) {
			robotsDirectory = FileUtil.getRobotsDir();
		}
		return robotsDirectory;
	}

	public void clearRobotList() {
		repository = null;
	}

	private List<FileSpecification> getSpecificationsInDirectory(File rootDir, File dir, String prefix, boolean isDevelopmentDirectory) {
		List<FileSpecification> robotList = Collections.synchronizedList(new ArrayList<FileSpecification>());

		// Order is important?
		String fileTypes[] = {
			".class", ".jar", ".team", ".jar.zip"
		};
		File files[] = dir.listFiles(new FileTypeFilter(fileTypes));

		if (files == null) {
			logError("Warning:  Unable to read directory " + dir);
			return robotList;
		}

		for (File file : files) {
			String fileName = file.getName();

			if (file.isDirectory()) {
				if (prefix.length() == 0) {
					int jidx = fileName.lastIndexOf(".jar_");

					if (jidx > 0 && jidx == fileName.length() - 5) {
						robotList.addAll(getSpecificationsInDirectory(file, file, "", isDevelopmentDirectory));
					} else {
						jidx = fileName.lastIndexOf(".zip_");
						if (jidx > 0 && jidx == fileName.length() - 5) {
							robotList.addAll(getSpecificationsInDirectory(file, file, "", isDevelopmentDirectory));
						} else {
							robotList.addAll(
									getSpecificationsInDirectory(rootDir, file, prefix + fileName + ".",
									isDevelopmentDirectory));
						}
					}
				} else {
					int odidx = fileName.indexOf("data.");

					if (odidx == 0) {
						renameOldDataDir(dir, file);
						continue;
					}

					int didx = fileName.lastIndexOf(".data");

					if (didx > 0 && didx == fileName.length() - 5) {
						continue;
					} // Don't process .data dirs
					robotList.addAll(
							getSpecificationsInDirectory(rootDir, file, prefix + fileName + ".", isDevelopmentDirectory));
				}
			} else if (fileName.indexOf("$") < 0 && fileName.indexOf("robocode") != 0) {
				FileSpecification cachedSpecification = getRobotDatabase().get(file.getPath());
				FileSpecification fileSpecification;

				// if cachedSpecification is null, then this is a new file
				if (cachedSpecification != null
						&& cachedSpecification.isSameFile(file.getPath(), file.length(), file.lastModified())) {
					// this file is unchanged
					fileSpecification = cachedSpecification;
				} else {
					fileSpecification = FileSpecification.createSpecification(this, file, rootDir, prefix,
							isDevelopmentDirectory);
					updateRobotDatabase(fileSpecification);
					write = true;
					if (fileSpecification instanceof JarSpecification) {
						String path = fileSpecification.getFilePath();

						path = path.substring(0, path.lastIndexOf(File.separatorChar));
						path = path.substring(path.lastIndexOf(File.separatorChar) + 1);

						if (path.equalsIgnoreCase("robots")) {
							// this file is changed
							updatedJarList.add(fileSpecification);
						}
					}
				}
				if (fileSpecification.isValid()) {
					robotList.add(fileSpecification);
				}
			}
		}
		return robotList;
	}

	private void saveRobotDatabase() {
		if (robotDatabase == null) {
			logError("Cannot save a null robot database.");
			return;
		}
		try {
			robotDatabase.store(new File(getRobotsDirectory(), "robot.database"));
		} catch (IOException e) {
			logError("IO Exception writing robot database: ", e);
		}
	}

	private void updateRobotDatabase(FileSpecification fileSpecification) {
		// Ignore files located in the robot cache
		String name = fileSpecification.getName();

		if (name == null || name.startsWith(".robotcache.")) {
			return;
		}

		String key = fileSpecification.getFilePath();

		if (fileSpecification instanceof RobotFileSpecification) {
			RobotFileSpecification robotFileSpecification = (RobotFileSpecification) fileSpecification;

			if (robotFileSpecification.isValid() && robotFileSpecification.verifyRobotName()
					&& robotFileSpecification.update()) {
				updateNoDuplicates(robotFileSpecification);
			} else {
				robotFileSpecification.setValid(false);
				getRobotDatabase().put(key, new ClassSpecification(robotFileSpecification));
				getRobotDatabase().put(key, robotFileSpecification);
			}
		} else if (fileSpecification instanceof JarSpecification) {
			getRobotDatabase().put(key, fileSpecification);
		} else if (fileSpecification instanceof TeamSpecification) {
			updateNoDuplicates(fileSpecification);
		} else if (fileSpecification instanceof ClassSpecification) {
			getRobotDatabase().put(key, fileSpecification);
		} else {
			System.out.println("Update robot database not possible for type " + fileSpecification.getFileType());
		}
	}

	private void updateNoDuplicates(FileSpecification spec) {
		String key = spec.getFilePath();

		WindowUtil.setStatus("Updating database: " + spec.getName());
		if (!spec.isDevelopmentVersion()
				&& getRobotDatabase().contains(spec.getFullClassName(), spec.getVersion(), false)) {
			FileSpecification existingSpec = getRobotDatabase().get(spec.getFullClassName(), spec.getVersion(), false);

			if (existingSpec == null) {
				getRobotDatabase().put(key, spec);
			} else if (!existingSpec.getUid().equals(spec.getUid())) {
				if (existingSpec.getFilePath().equals(spec.getFilePath())) {
					getRobotDatabase().put(key, spec);
				} else // if (duplicatePrompt)
				{
					File existingSource = existingSpec.getJarFile(); // getRobotsDirectory(),getRobotCache());
					File newSource = spec.getJarFile(); // getRobotsDirectory(),getRobotCache());

					if (existingSource != null && newSource != null) {
						long t1 = existingSource.lastModified();
						long t2 = newSource.lastModified();

						if (t1 > t2) {
							if (!existingSource.renameTo(new File(existingSource.getPath() + ".invalid"))){
								Logger.logError("Can't move " + existingSource.toString());
							}
							getRobotDatabase().remove(existingSpec.getFilePath());
							getRobotDatabase().put(key, spec);
							conflictLog(
									"Renaming " + existingSource + " to invalid, as it contains a robot " + spec.getName()
									+ " which conflicts with the same robot in " + newSource);
						} else {
							if (!newSource.renameTo(new File(newSource.getPath() + ".invalid"))){
								Logger.logError("Can't move " + newSource.toString());
							}
							conflictLog(
									"Renaming " + newSource + " to invalid, as it contains a robot " + spec.getName()
									+ " which conflicts with the same robot in " + existingSource);
						}
					}
				}
			} else {
				spec.setDuplicate(true);
				getRobotDatabase().put(key, spec);
			}
		} else {
			getRobotDatabase().put(key, spec);
		}
	}

	private void conflictLog(String s) {
		logError(s);

		File f = new File(FileUtil.getCwd(), "conflict.logError");
		FileWriter writer = null;
		BufferedWriter out = null;

		try {
			writer = new FileWriter(f.getPath(), true);
			out = new BufferedWriter(writer);
			out.write(s + "\n");
		} catch (IOException e) {
			logError("Warning:  Could not write to conflict.logError");
		} finally {
			if (out != null) {
				try {
					out.close();
				} catch (IOException ignored) {}
			}
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException ignored) {}
			}
		}
	}

	public boolean cleanupOldSampleRobots(boolean delete) {
		// TODO: Needs to be updated?
		String oldSampleList[] = {
			"Corners.java", "Crazy.java", "Fire.java", "MyFirstRobot.java", "RamFire.java", "SittingDuck.java",
			"SpinBot.java", "Target.java", "Tracker.java", "TrackFire.java", "Walls.java", "Corners.class",
			"Crazy.class", "Fire.class", "MyFirstRobot.class", "RamFire.class", "SittingDuck.class", "SpinBot.class",
			"Target.class", "Target$1.class", "Tracker.class", "TrackFire.class", "Walls.class"
		};

		File robotDir = getRobotsDirectory();

		if (robotDir.isDirectory()) {
			for (File sampleBot : robotDir.listFiles()) {
				if (!sampleBot.isDirectory()) {
					for (String oldSampleBot : oldSampleList) {
						if (sampleBot.getName().equals(oldSampleBot)) {
							logMessage("Deleting old sample file: " + sampleBot.getName());
							if (delete) {
								if (!sampleBot.delete()){
									Logger.logError("Can't detele " + sampleBot.toString());
								}
							} else {
								return true;
							}
						}
					}
				}
			}
		}
		return false;
	}

	private void renameOldDataDir(File dir, File f) {
		String name = f.getName();
		String botName = name.substring(name.indexOf(".") + 1);
		File newFile = new File(dir, botName + ".data");

		if (!newFile.exists()) {
			File oldFile = new File(dir, name);

			logError("Renaming " + oldFile.getName() + " to " + newFile.getName());
			if (!oldFile.renameTo(newFile)){
				Logger.logError("Can't move " + oldFile.toString());
			}
		}
	}

	/**
	 * Gets the manager.
	 *
	 * @return Returns a RobocodeManager
	 */
	public RobocodeManager getManager() {
		return manager;
	}

	public List<FileSpecification> getRobotSpecificationsList() {
		loadRobotRepository();
		return repository.getRobotSpecificationsList(false, false, false, false, false, false);
	}

	public List<FileSpecification> getRobotSpecificationsList(boolean onlyWithSource, boolean onlyWithPackage, boolean onlyRobots, boolean onlyDevelopment, boolean onlyNotDevelopment, boolean ignoreTeamRobots) {
		loadRobotRepository();
		return repository.getRobotSpecificationsList(onlyWithSource, onlyWithPackage, onlyRobots, onlyDevelopment, onlyNotDevelopment, ignoreTeamRobots);
	}

	public FileSpecification getRobot(String fullClassNameWithVersion) {
		loadRobotRepository();
		return repository.get(fullClassNameWithVersion);
	}
}