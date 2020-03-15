/*
 * Sonar C++ Plugin (Community)
 * Copyright (C) 2010-2020 SonarOpenCommunity
 * http://github.com/SonarOpenCommunity/sonar-cxx
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.cxx.sensors.drmemory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.config.Configuration;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.cxx.CxxMetrics;
import org.sonar.cxx.sensors.drmemory.DrMemoryParser.DrMemoryError;
import org.sonar.cxx.sensors.drmemory.DrMemoryParser.DrMemoryError.Location;
import org.sonar.cxx.sensors.utils.CxxIssuesReportSensor;
import org.sonar.cxx.utils.CxxReportIssue;

/**
 * Dr. Memory is a memory monitoring tool capable of identifying memory-related programming errors such as accesses of
 * uninitialized memory, accesses to not addressable memory (including outside of allocated heap units and heap
 * underflow and overflow), accesses to freed memory, double frees, memory leaks, and (on Windows) handle leaks, GDI API
 * usage errors, and accesses to unreserved thread local storage slots. See also: http://drmemory.org
 *
 * @author asylvestre
 */
public class CxxDrMemorySensor extends CxxIssuesReportSensor {

  public static final String REPORT_PATH_KEY = "sonar.cxx.drmemory.reportPath";
  private static final Logger LOG = Loggers.get(CxxDrMemorySensor.class);
  private static final String DEFAULT_CHARSET_DEF = StandardCharsets.UTF_8.name();

  /**
   * CxxDrMemorySensor for Doctor Memory Sensor
   *
   * @param settings sensor configuration
   */
  public CxxDrMemorySensor(Configuration settings) {
    super(settings, REPORT_PATH_KEY, CxxDrMemoryRuleRepository.KEY);
  }

  private static String getFrameText(Location frame, int frameNr) {
    StringBuilder sb = new StringBuilder(512);
    sb.append("#").append(frameNr).append(" ").append(frame.getFile()).append(":").append(frame.getLine());
    return sb.toString();
  }

  public static List<PropertyDefinition> properties() {
    String subcateg = "Dr Memory";
    return Collections.unmodifiableList(Arrays.asList(
      PropertyDefinition.builder(REPORT_PATH_KEY)
        .name("Dr Memory report(s)")
        .description("Path to <a href='http://drmemory.org/'>Dr. Memory</a> reports(s), relative to projects root."
                       + USE_ANT_STYLE_WILDCARDS)
        .subCategory(subcateg)
        .onQualifiers(Qualifiers.PROJECT)
        .multiValues(true)
        .build()
    ));
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name(getLanguage().getName() + " DrMemorySensor")
      .onlyOnLanguage(getLanguage().getKey())
      .createIssuesForRuleRepository(getRuleRepositoryKey())
      .onlyWhenConfiguration(conf -> conf.hasKey(getReportPathKey()));
  }

  private boolean frameIsInProject(SensorContext context, Location frame) {
    return getInputFileIfInProject(context, frame.getFile()) != null;
  }

  private Location getLastOwnFrame(SensorContext context, DrMemoryError error) {
    for (Location frame : error.getStackTrace()) {
      if (frameIsInProject(context, frame)) {
        return frame;
      }
    }
    return null;
  }

  @Override
  protected void processReport(final SensorContext context, File report) {
    LOG.debug("Parsing 'Dr Memory' format");

    for (DrMemoryError error : DrMemoryParser.parse(report, DEFAULT_CHARSET_DEF)) {
      if (error.getStackTrace().isEmpty()) {
        CxxReportIssue moduleIssue = new CxxReportIssue(error.getType().getId(), null,
                                                        null, error.getMessage());
        saveUniqueViolation(context, moduleIssue);
      } else {
        Location lastOwnFrame = getLastOwnFrame(context, error);
        if (lastOwnFrame == null) {
          LOG.warn("Cannot find a project file to assign the DrMemory error '{}' to", error);
          continue;
        }
        CxxReportIssue fileIssue = new CxxReportIssue(error.getType().getId(),
                                                      lastOwnFrame.getFile(), lastOwnFrame.getLine().toString(), error
                                                      .getMessage());

        // add all frames as secondary locations
        int frameNr = 0;
        for (Location frame : error.getStackTrace()) {
          boolean frameIsInProject = frameIsInProject(context, frame);
          String mappedPath = (frameIsInProject) ? frame.getFile() : lastOwnFrame.getFile();
          Integer mappedLine = (frameIsInProject) ? frame.getLine() : lastOwnFrame.getLine();
          fileIssue.addLocation(mappedPath, mappedLine.toString(), getFrameText(frame, frameNr));
          ++frameNr;
        }
        saveUniqueViolation(context, fileIssue);
      }
    }
  }

  @Override
  protected String getMetricKey() {
    return CxxMetrics.DRMEMORY_SENSOR_ISSUES_KEY;
  }

}
