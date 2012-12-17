/* ********************************************************************
    Licensed to Jasig under one or more contributor license
    agreements. See the NOTICE file distributed with this work
    for additional information regarding copyright ownership.
    Jasig licenses this file to you under the Apache License,
    Version 2.0 (the "License"); you may not use this file
    except in compliance with the License. You may obtain a
    copy of the License at:

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on
    an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied. See the License for the
    specific language governing permissions and limitations
    under the License.
*/
package org.bedework.timezones.common;

import org.bedework.timezones.common.Differ.DiffListEntry;
import org.bedework.timezones.common.db.DbCachedData;

import edu.rpi.cmt.timezones.model.ObservanceType;
import edu.rpi.cmt.timezones.model.TimezoneType;
import edu.rpi.cmt.timezones.model.TimezonesType;
import edu.rpi.cmt.timezones.model.TzdataType;
import edu.rpi.sss.util.DateTimeUtil;

import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Dur;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.PeriodList;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.UtcOffset;
import net.fortuna.ical4j.model.component.Observance;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.util.TimeZones;

import org.apache.log4j.Logger;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/** Common code for the timezone service.
 *
 *   @author Mike Douglass
 */
public class TzServerUtil {
  private static String appname = "tzsvr";

  private static TzServerUtil instance;

  /* A URL for retrieving the tzdata jar */
  static String tzdataUrl;

  private static Object locker = new Object();

  /* ======================= Stats ======================= */

  static long gets;
  static long cacheHits;
  static long reads;
  static long nameLists;
  static long aliasReads;
  static long conversions;
  static long conversionsMillis;
  static long tzfetches;
  static long reloads;
  static long reloadsMillis;
  static long expandFetches;
  static long expandHits;
  static long expands;
  static long expandsMillis;

  /* ======================= Error codes ======================= */

  /** Unable to retrieve the data */
  public static final String errorNodata = "org.tserver.no.data";

  /* ======================= Caching ======================= */

  private CachedData cache;

  /** Time we last fetched the data */
  public static long lastDataFetch;

  /**
   * @throws TzException
   */
  private TzServerUtil() throws TzException {
    getcache();
  }

  /* ====================================================================
   *                   Static methods
   * ==================================================================== */

  /**
   * @return a singleton instance
   * @throws TzException
   */
  public static TzServerUtil getInstance() throws TzException {
    if (instance != null) {
      return instance;
    }

    synchronized (locker) {
      if (instance != null) {
        return instance;
      }

      instance = new TzServerUtil();
    }

    return instance;
  }

  /**
   * @return appname
   */
  public static String getAppname() {
    return appname;
  }

  /** Set before calling getInstance if overriding config
   *
   * @param val
   */
  public static void setTzdataUrl(final String val) {
    tzdataUrl = val;
  }

  /**
   * @return tzdataUrl
   */
  public static String getTzdataUrl() {
    return tzdataUrl;
  }

  /** Initial value from config of url of server we refresh from.
   * Ignore if we are a primary server
   *
   * @return String
   * @throws TzException
   */
  public static String getInitialPrimaryUrl() throws TzException {
    try {
      return TzsvrOptionsFactory.getOptions().getGlobalStringProperty("initialPrimaryUrl");
    } catch (Throwable t) {
      throw new TzException(t);
    }
  }

  /** Initial value from config for are we a primary server?
   * @return boolean
   * @throws TzException
   */
  public static boolean getInitialPrimaryServer() throws TzException {
    try {
      return TzsvrOptionsFactory.getOptions().getGlobalBoolProperty("initialPrimaryServer");
    } catch (Throwable t) {
      throw new TzException(t);
    }
  }

  /**
   * @return long Refresh interval - seconds
   * @throws TzException
   */
  public static long getInitialRefreshInterval() throws TzException {
    try {
      return TzsvrOptionsFactory.getOptions().getGlobalIntProperty("initialRefreshInterval");
    } catch (Throwable t) {
      throw new TzException(t);
    }
  }

  /** Cause a refresh of the data
   *
   * @throws TzException
   */
  public static void fireRefresh() throws TzException {
    if (getInstance().cache != null) {
      try {
        getInstance().cache.stop();
      } catch (Throwable t) {
        error(t);
        error("Error stopping cache");
      }

      getInstance().cache = null;
    }
    getInstance().getcache();
  }

  /** Cause data to be checked against primary
   *
   * @throws TzException
   */
  public static void fireCheck() throws TzException {
    getInstance().cache.checkData();
  }

  /** Compare data pointed to by tzdataUrl with the given data then update.
   *
   * @param tzdataUrl
   * @return info lines.
   * @throws TzException
   */
  public static List<String> updateData(final String tzdataUrl) throws TzException {
    TzServerUtil util = getInstance();

    Differ diff = new Differ();

    List<DiffListEntry> dles = diff.compare(tzdataUrl, util.cache);

    List<String> out = new ArrayList<String>();

    if (dles == null) {
      out.add("No data returned");
      return out;
    }

    for (DiffListEntry dle: dles) {
      out.add(dle.toShortString());
    }

    if (dles.size() == 0) {
      return out;
    }

    try {
      DatatypeFactory dtf = DatatypeFactory.newInstance();

      XMLGregorianCalendar xgc = dtf.newXMLGregorianCalendar(new GregorianCalendar());
      xgc.setFractionalSecond(null);
      String dtstamp = xgc.normalize().toXMLFormat();

      util.cache.updateData(dtstamp, dles);

      /* Now do a reload to ensure we have the new data in the cache */

      fireRefresh();

      return out;
    } catch (TzException te) {
      throw te;
    } catch (Throwable t) {
      throw new TzException(t);
    }
  }

  /** Compare data pointed to by tzdataUrl with the given data.
   *
   * @param tzdataUrl
   * @return info lines.
   * @throws TzException
   */
  public static List<String> compareData(final String tzdataUrl) throws TzException {
    TzServerUtil util = getInstance();

    Differ diff = new Differ();

    List<DiffListEntry> dles = diff.compare(tzdataUrl, util.cache);

    List<String> out = new ArrayList<String>();

    if (dles == null) {
      out.add("No data returned");
      return out;
    }

    for (DiffListEntry dle: dles) {
      out.add(dle.toShortString());
    }

    return out;
  }

  /**
   * @return stats for the service
   * @throws TzException
   */
  public static List<Stat> getStats() throws TzException {
    List<Stat> stats = new ArrayList<Stat>();

    stats.add(new Stat("Gets", String.valueOf(gets)));
    stats.add(new Stat("Hits", String.valueOf(cacheHits)));
    stats.add(new Stat("Name lists", String.valueOf(nameLists)));
    stats.add(new Stat("Reads", String.valueOf(reads)));
    stats.add(new Stat("conversions",
                       String.valueOf(conversions),
                       String.valueOf(conversionsMillis)));
    stats.add(new Stat("tzfetches", String.valueOf(tzfetches)));
    stats.add(new Stat("tzreloads",
                       String.valueOf(reloads),
                       String.valueOf(reloadsMillis)));
    stats.add(new Stat("expands",
                       String.valueOf(expands),
                       String.valueOf(expandsMillis)));

    if (getInstance().cache != null) {
      stats.addAll(getInstance().cache.getStats());
    }

    return stats;
  }

  /* ====================================================================
   *                   Instance methods
   * ==================================================================== */

  /** Stop any running threads.
   *
   * @throws TzException
   */
  public void stop() throws TzException {
    if (cache != null) {
      cache.stop();
    }
  }

  /** Url of server we refresh from. Ignore if we are a primary server
   *
   * @param val    String
   * @throws TzException
   */
  public void setPrimaryUrl(final String val) throws TzException {
    cache.setPrimaryUrl(val);
  }

  /**
   * @return String
   * @throws TzException
   */
  public String getPrimaryUrl() throws TzException {
    return cache.getPrimaryUrl();
  }

  /** Are we a primary server?
   *
   * @param val    boolean
   * @throws TzException
   */
  public void setPrimaryServer(final boolean val) throws TzException {
    cache.setPrimaryServer(val);
  }

  /** Are we a primary server?
   * @return boolean
   * @throws TzException
   */
  public boolean getPrimaryServer() throws TzException {
    return cache.getPrimaryServer();
  }

  /** Refresh interval - seconds
   *
   * @param val
   * @throws TzException
   */
  public void setRefreshInterval(final long val) throws TzException {
    cache.setRefreshInterval(val);
  }

  /**
   * @return long Refresh interval - seconds
   * @throws TzException
   */
  public long getRefreshInterval() throws TzException {
    return cache.getRefreshInterval();
  }

  /**
   * @return the data dtsamp
   * @throws TzException
   */
  public Date getDtstamp() throws TzException {
    String dtst = cache.getDtstamp();
    Date dtstamp;

    if (dtst == null) {
      dtstamp = new DateTime(lastDataFetch);
    } else {
      try {
        dtstamp = DateTimeUtil.fromRfcDateTimeUTC(dtst);
      } catch (Throwable t) {
        throw new TzException(t);
      }
    }

    return dtstamp;
  }

  /**
   * @return names from the zip file.
   * @throws TzException
   */
  public SortedSet<String> getNames() throws TzException {
    nameLists++;

    return cache.getNameList();
  }

  /**
   * @param name
   * @return spec
   * @throws TzException
   */
  public String getTz(final String name) throws TzException {
    return cache.getCachedVtz(name);
  }

  /**
   * @return all specs
   * @throws TzException
   */
  public Collection<String> getAllTzs() throws TzException {
    return cache.getAllCachedVtzs();
  }

  /**
   * @param name
   * @return spec
   * @throws TzException
   */
  public String getAliasedTz(final String name) throws TzException {
    return cache.getAliasedCachedVtz(name);
  }

  /**
   * @return String value of aliases file.
   * @throws TzException
   */
  public String getAliasesStr() throws TzException {
    return cache.getAliasesStr();
  }

  /**
   * @param tzid
   * @return list of aliases or null
   * @throws TzException
   */
  public SortedSet<String> findAliases(final String tzid) throws TzException {
    return cache.findAliases(tzid);
  }

  /**
   * @param time
   * @param tzid
   * @return String utc date
   * @throws Throwable
   */
  public String getUtc(final String time,
                       final String tzid) throws Throwable {
    if (DateTimeUtil.isISODateTimeUTC(time)) {
      // Already UTC
      return time;
    }

    if (!DateTimeUtil.isISODateTime(time)) {
      return null;  // Bad datetime
    }

    conversions++;
    long smillis = System.currentTimeMillis();

    TimeZone tz = fetchTimeZone(tzid);

    DateFormat formatTd  = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
    formatTd.setTimeZone(tz);

    Date date = formatTd.parse(time);
    String utc;

    synchronized (cal) {
      cal.clear();
      cal.setTime(date);

      //formatTd.setTimeZone(utctz);
      //trace("formatTd with utc: " + formatTd.format(date));

      StringBuilder sb = new StringBuilder();
      digit4(sb, cal.get(Calendar.YEAR));
      digit2(sb, cal.get(Calendar.MONTH) + 1); // Month starts at 0
      digit2(sb, cal.get(Calendar.DAY_OF_MONTH));
      sb.append('T');
      digit2(sb, cal.get(Calendar.HOUR_OF_DAY));
      digit2(sb, cal.get(Calendar.MINUTE));
      digit2(sb, cal.get(Calendar.SECOND));
      sb.append('Z');

      utc = sb.toString();
    }

    conversionsMillis += System.currentTimeMillis() - smillis;

    return utc;
  }

  /** Convert from local time in fromTzid to local time in toTzid. If dateTime is
   * already an iso utc date time fromTzid may be null.
   *
   * @param dateTime
   * @param fromTzid
   * @param toTzid
   * @return String time in given timezone
   * @throws Throwable
   */
  public String convertDateTime(final String dateTime, final String fromTzid,
                                final String toTzid) throws Throwable {
    String UTCdt = null;
    if (DateTimeUtil.isISODateTimeUTC(dateTime)) {
      // Already UTC
      UTCdt = dateTime;
    } else if (!DateTimeUtil.isISODateTime(dateTime)) {
      return null;  // Bad datetime
    } else if (toTzid == null) {
      return null;  // Bad toTzid
    } else {
      UTCdt = getUtc(dateTime, fromTzid);
      conversions--; // avoid double inc
    }

    conversions++;
    long smillis = System.currentTimeMillis();

    // Convert to time in toTzid

    Date dt = DateTimeUtil.fromISODateTimeUTC(UTCdt);

    TimeZone tz = fetchTimeZone(toTzid);
    if (tz == null) {
      return null;
    }

    String cdt = DateTimeUtil.isoDateTime(dt, tz);
    conversionsMillis += System.currentTimeMillis() - smillis;

    return cdt;
  }

  /**
   * @param changedSince - null or dtstamp value
   * @return list of summary info
   * @throws TzException
   */
  public List<TimezoneType> getTimezones(final String changedSince) throws TzException {
    return cache.getTimezones(changedSince);
  }

  /**
   * @param name - non null name for partial match
   * @return list of summary info
   * @throws TzException
   */
  public List<TimezoneType> findTimezones(final String name) throws TzException {
    return cache.findTimezones(name);
  }

  private static class ObservanceWrapper implements Comparable<ObservanceWrapper> {
    ObservanceType ot;

    ObservanceWrapper(final ObservanceType ot) {
      this.ot = ot;
    }

    @Override
    public int compareTo(final ObservanceWrapper o) {
      return ot.getOnset().compareTo(o.ot.getOnset());
    }
  }

  /**
   * @param tzid
   * @param start
   * @param end
   * @return expansion or null
   * @throws Throwable
   */
  public ExpandedMapEntry getExpanded(final String tzid,
                                      final String start,
                                      final String end) throws Throwable {
    expandFetches++;

    ExpandedMapEntryKey emek = makeExpandedKey(tzid, start, end);

    ExpandedMapEntry tzs = cache.getExpanded(emek);
    if (tzs != null) {
      expandHits++;
      return tzs;
    }

    long smillis = System.currentTimeMillis();

    TimeZone tz = fetchTimeZone(tzid);
    if (tz == null) {
      return null;
    }

    VTimeZone vtz = tz.getVTimeZone();

    DateTime dtstart = new DateTime(emek.getStart());
    DateTime dtend = new DateTime(emek.getEnd());

    dtstart.setTimeZone(tz);
    dtend.setTimeZone(tz);

    Period p = new Period(dtstart, dtend);

    ComponentList cl = vtz.getObservances();

    TreeSet<ObservanceWrapper> obws = new TreeSet<ObservanceWrapper>();

    for (Object o: cl) {
      Observance ob = (Observance)o;

      PeriodList pl = ob.calculateRecurrenceSet(p);

      for (Object po: pl) {
        Period onsetPer = (Period)po;

        ObservanceType ot = new ObservanceType();

        ot.setName(ob.getName());
        ot.setOnset(onsetPer.getStart().toString());

        ot.setUtcOffsetFrom(delimited(ob.getOffsetFrom().getOffset()));

        ot.setUtcOffsetTo(delimited(ob.getOffsetTo().getOffset()));

        obws.add(new ObservanceWrapper(ot));
      }
    }

    TzdataType tzd = new TzdataType();

    tzd.setTzid(tzid);
    for (ObservanceWrapper ow: obws) {
      tzd.getObservances().add(ow.ot);
    }

    TimezonesType tzt = new TimezonesType();

    tzt.setDtstamp(getDtstamp());
    tzt.getTzdata().add(tzd);

    tzs = new ExpandedMapEntry(String.valueOf(smillis), tzt);

    cache.setExpanded(emek, tzs);

    expandsMillis += System.currentTimeMillis() - smillis;
    expands++;

    return tzs;
  }

  private String delimited(final UtcOffset val) {
    String offset = val.toString();

    int pos = 0;
    StringBuilder sb = new StringBuilder();

    if (offset.startsWith("-") || offset.startsWith("+")) {
      sb.append(offset.charAt(0));
      pos = 1;
    }

    sb.append(offset.substring(pos, pos + 2));
    sb.append(':');
    pos += 2;
    sb.append(offset.substring(pos, pos + 2));
    pos += 2;

    if (pos < offset.length()) {
      sb.append(':');
      sb.append(offset.substring(pos, pos + 2));
    }

    return sb.toString();
  }

  /** Get a timezone object from the server given the id.
   *
   * @param tzid
   * @return TimeZone with id or null
   * @throws TzException
   */
  public TimeZone fetchTimeZone(final String tzid) throws TzException {
    tzfetches++;

    return cache.getTimeZone(tzid);
  }

  /* ====================================================================
   *                   Convenience methods
   * ==================================================================== */

  /**
   * @return an ical Calendar prefix
   */
  public static String getCalHdr() {
    return "BEGIN:VCALENDAR\n" +
           "VERSION:2.0\n" +
           "CALSCALE:GREGORIAN\n" +
           "PRODID:/bedework.org//NONSGML Bedework//EN\n";
  }

  /**
   * @return an ical Calendar trailer
   */
  public static String getCalTlr() {
    return "END:VCALENDAR\n";
  }

  /**
   * @return an etag based on when we refreshed data
   * @throws TzException
   */
  public String getEtag() throws TzException {
    StringBuilder val = new StringBuilder();

    val.append("\"");
    val.append(DateTimeUtil.rfcDateTimeUTC(getDtstamp()));
    val.append("\"");

    return val.toString();
  }

  /* ====================================================================
   *                   Private methods
   * ==================================================================== */

  private ExpandedMapEntryKey makeExpandedKey(final String tzid,
                                              final String start,
                                              final String end) throws TzException {
    String st = start;

    if (st == null) {
      String date = new net.fortuna.ical4j.model.Date().toString();

      st = date + "T000000Z";
    }

    String e = end;
    if (e == null) {
      Dur dur = new Dur("P520W");

      String date = new net.fortuna.ical4j.model.Date(dur.getTime(new Date())).toString();
      e = date + "T000000Z";
    }

    return new ExpandedMapEntryKey(tzid, st, e);
  }

  private void getcache() throws TzException {
    try {
      cache = new DbCachedData();
    } catch (TzException te) {
      error(te);
      cache = null;
    }

    if (cache == null) {
      cache = new ZipCachedData(tzdataUrl);
    }
  }

  private static Calendar cal = Calendar.getInstance();
  private static java.util.TimeZone utctz;

  static {
    try {
      utctz = TimeZone.getTimeZone(TimeZones.UTC_ID);
    } catch (Throwable t) {
      throw new RuntimeException("Unable to initialise UTC timezone");
    }
    cal.setTimeZone(utctz);
  }

  /**
   * @return Logger
   */
  static Logger getLogger() {
    return Logger.getLogger(TzServerUtil.class);
  }

  /** Debug
   *
   * @param msg
   */
  static void debugMsg(final String msg) {
    getLogger().debug(msg);
  }

  /** Info messages
   *
   * @param msg
   */
  static void logIt(final String msg) {
    getLogger().info(msg);
  }

  static void error(final String msg) {
    getLogger().error(msg);
  }

  static void info(final String msg) {
    getLogger().info(msg);
  }

  static void error(final Throwable t) {
    getLogger().error(TzServerUtil.class, t);
  }

  private static void digit2(final StringBuilder sb, final int val) throws Throwable {
    if (val > 99) {
      throw new Exception("Bad date");
    }
    if (val < 10) {
      sb.append("0");
    }
    sb.append(val);
  }

  private static void digit4(final StringBuilder sb, final int val) throws Throwable {
    if (val > 9999) {
      throw new Exception("Bad date");
    }
    if (val < 10) {
      sb.append("000");
    } else if (val < 100) {
      sb.append("00");
    } else if (val < 1000) {
      sb.append("0");
    }
    sb.append(val);
  }
}
