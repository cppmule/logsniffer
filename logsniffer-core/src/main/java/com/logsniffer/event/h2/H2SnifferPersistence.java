/*******************************************************************************
 * logsniffer, open source tool for viewing, monitoring and analysing log data.
 * Copyright (c) 2015 Scaleborn UG, www.scaleborn.com
 *
 * logsniffer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * logsniffer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package com.logsniffer.event.h2;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.logsniffer.aspect.AspectProvider;
import com.logsniffer.aspect.PostAspectProvider;
import com.logsniffer.aspect.sql.QueryAdaptor;
import com.logsniffer.config.BeanConfigFactoryManager;
import com.logsniffer.config.ConfigException;
import com.logsniffer.event.IncrementData;
import com.logsniffer.event.LogEntryReaderStrategy;
import com.logsniffer.event.Publisher;
import com.logsniffer.event.Publisher.PublisherWrapper;
import com.logsniffer.event.Scanner;
import com.logsniffer.event.Scanner.LogEntryReaderStrategyWrapper;
import com.logsniffer.event.Scanner.ScannerWrapper;
import com.logsniffer.event.Sniffer;
import com.logsniffer.event.SnifferPersistence;
import com.logsniffer.event.SnifferScheduler.ScheduleInfo;
import com.logsniffer.model.Log;
import com.logsniffer.model.LogInputStream;
import com.logsniffer.model.LogSource;
import com.logsniffer.model.support.JsonLogPointer;
import com.logsniffer.util.LazyList;
import com.logsniffer.util.LazyList.ListFactory;
import com.logsniffer.util.PageableResult;

/**
 * H2 persistence for sniffers.
 * 
 * @author mbok
 * 
 */
@Component
public class H2SnifferPersistence implements SnifferPersistence {
	private final Logger logger = LoggerFactory.getLogger(getClass());

	/**
	 * Row mapper for sniffer's.
	 * 
	 * @author mbok
	 * 
	 */
	private class SnifferRowMapper implements RowMapper<AspectSniffer> {
		private static final String SQL_PROJECTION = "SELECT ID, NAME, SCANNER_CONFIG, READER_STRATEGY_CONFIG, PUBLISHERS_CONFIG, CRON_EXPR,"
				+ " SOURCE FROM SNIFFERS ORDER BY NAME";

		@Override
		public AspectSniffer mapRow(final ResultSet rs, final int rowNum)
				throws SQLException {
			AspectSniffer sniffer = new AspectSniffer();
			sniffer.setId(rs.getLong("ID"));
			sniffer.setName(rs.getString("NAME"));
			sniffer.setScheduleCronExpression(rs.getString("CRON_EXPR"));
			sniffer.setLogSourceId(rs.getLong("SOURCE"));
			final String strategyConfigStr = rs
					.getString("READER_STRATEGY_CONFIG");
			sniffer.setReaderStrategy(new LogEntryReaderStrategyWrapper() {
				@Override
				public LogEntryReaderStrategy getWrapped()
						throws ConfigException {
					return configManager.createBeanFromJSON(
							LogEntryReaderStrategy.class, strategyConfigStr);
				}

			});
			final String scannerConfigStr = rs.getString("SCANNER_CONFIG");
			if (StringUtils.isNotEmpty(scannerConfigStr)) {
				sniffer.setScanner(new ScannerWrapper() {
					@Override
					public Scanner getWrapped() throws ConfigException {
						return configManager.createBeanFromJSON(Scanner.class,
								scannerConfigStr);
					}
				});

			}
			final String publishersConfigStr = rs
					.getString("PUBLISHERS_CONFIG");
			if (StringUtils.isNotEmpty(publishersConfigStr)) {
				sniffer.setPublishers(new LazyList<Publisher>(
						new ListFactory<Publisher>() {
							@Override
							public List<Publisher> createList() {
								List<Publisher> pubs = new ArrayList<Publisher>();
								JSONArray jsonArray = JSONArray
										.fromObject(publishersConfigStr);
								for (final Object pubJson : jsonArray) {
									pubs.add(new PublisherWrapper() {
										@Override
										public Publisher getWrapped()
												throws ConfigException {
											return configManager
													.createBeanFromJSON(
															Publisher.class,
															pubJson.toString());
										}

									});
								}
								return pubs;
							}
						}));
			}
			return sniffer;
		}
	}

	private class SnifferCreator implements PreparedStatementCreator {
		private static final String SQL_SET = "SNIFFERS SET NAME=?, CRON_EXPR=?, SOURCE=?, SCANNER_CONFIG=?, PUBLISHERS_CONFIG=?, READER_STRATEGY_CONFIG=?";
		private static final String SQL_INSERT = "INSERT INTO " + SQL_SET;
		private static final String SQL_UPDATE = "UPDATE " + SQL_SET
				+ " WHERE ID=?";
		private final Sniffer sniffer;
		private final boolean insert;

		private SnifferCreator(final boolean insert, final Sniffer sniffer) {
			this.insert = insert;
			this.sniffer = sniffer;
		}

		@Override
		public PreparedStatement createPreparedStatement(final Connection con)
				throws SQLException {
			PreparedStatement ps = con.prepareStatement(insert ? SQL_INSERT
					: SQL_UPDATE);
			try {
				int c = 1;
				ps.setString(c++, sniffer.getName());
				ps.setString(c++, sniffer.getScheduleCronExpression());
				ps.setLong(c++, sniffer.getLogSourceId());
				if (sniffer.getScanner() != null) {
					ps.setString(c++, configManager
							.saveBeanToJSON(ScannerWrapper.unwrap(sniffer
									.getScanner())));
				} else {
					ps.setString(c++, (String) null);
				}
				if (sniffer.getPublishers() != null) {
					StringBuilder jsonArray = new StringBuilder("[");
					for (Publisher pub : sniffer.getPublishers()) {
						if (jsonArray.length() > 1) {
							jsonArray.append(",");
						}
						jsonArray.append(configManager
								.saveBeanToJSON(PublisherWrapper.unwrap(pub)));
					}
					jsonArray.append("]");
					ps.setString(c++, jsonArray.toString());
				} else {
					ps.setString(c++, "[]");
				}
				if (sniffer.getReaderStrategy() != null) {
					ps.setString(c++, configManager
							.saveBeanToJSON(LogEntryReaderStrategyWrapper
									.unwrap(sniffer.getReaderStrategy())));
				} else {
					ps.setString(c++, (String) null);
				}
				if (!insert) {
					ps.setLong(c++, sniffer.getId());
				}
				return ps;
			} catch (ConfigException e) {
				throw new SQLException("Not able to serialize config data", e);
			}
		}

	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private BeanConfigFactoryManager configManager;

	@Override
	public SnifferListBuilder getSnifferListBuilder() {
		return new SnifferListBuilder() {
			private AspectProvider<AspectSniffer, Integer> eventsCounter;

			private QueryAdaptor<AspectSniffer, ScheduleInfo> scheduleInfoAdaptor;

			@Override
			public PageableResult<AspectSniffer> list() {
				String query = SnifferRowMapper.SQL_PROJECTION;
				RowMapper<AspectSniffer> rowMapper = new SnifferRowMapper();
				List<Object> args = new ArrayList<Object>();
				if (eventsCounter instanceof QueryAdaptor) {
					QueryAdaptor<AspectSniffer, Integer> outer = (QueryAdaptor<AspectSniffer, Integer>) eventsCounter;
					query = outer.getQuery(query);
					rowMapper = outer.getRowMapper(rowMapper);
					args = outer.getQueryArgs(args);
				}
				if (scheduleInfoAdaptor != null) {
					query = scheduleInfoAdaptor.getQuery(query);
					rowMapper = scheduleInfoAdaptor.getRowMapper(rowMapper);
					args = scheduleInfoAdaptor.getQueryArgs(args);
				}
				List<AspectSniffer> sniffers = jdbcTemplate.query(query,
						args.toArray(new Object[args.size()]), rowMapper);
				if (eventsCounter instanceof PostAspectProvider) {
					((PostAspectProvider<AspectSniffer, Integer>) eventsCounter)
							.injectAspect(sniffers);
				}
				return new PageableResult<SnifferPersistence.AspectSniffer>(-1,
						sniffers);
			}

			@Override
			public SnifferListBuilder withEventsCounter(
					final AspectProvider<AspectSniffer, Integer> eventsCounter) {
				this.eventsCounter = eventsCounter;
				return this;
			}

			@Override
			public SnifferListBuilder withScheduleInfo(
					final QueryAdaptor<AspectSniffer, ScheduleInfo> adaptor) {
				this.scheduleInfoAdaptor = adaptor;
				return this;
			}
		};
	}

	@Override
	public Sniffer getSniffer(final long id) {
		List<AspectSniffer> sniffers = jdbcTemplate.query("SELECT * FROM ("
				+ SnifferRowMapper.SQL_PROJECTION + ") WHERE ID=?",
				new Object[] { id }, new SnifferRowMapper());
		if (sniffers.size() == 1) {
			return sniffers.get(0);
		}
		return null;
	}

	@Override
	public IncrementData getIncrementData(final Sniffer sniffer,
			final LogSource<? extends LogInputStream> source, final Log log) {
		List<IncrementData> idatas = jdbcTemplate
				.query("SELECT NEXT_POINTER, DATA FROM SNIFFERS_SCANNER_IDATA WHERE SNIFFER=? AND SOURCE=? AND LOG=?",
						new Object[] { sniffer.getId(), source.getId(),
								log.getPath() },
						new RowMapper<IncrementData>() {
							@Override
							public IncrementData mapRow(final ResultSet rs,
									final int rowNum) throws SQLException {
								IncrementData data = new IncrementData();
								data.setData(JSONObject.fromObject(rs
										.getString("DATA")));
								data.setNextOffset(new JsonLogPointer(rs
										.getString("NEXT_POINTER")));
								return data;
							}
						});
		if (idatas.size() == 0) {
			logger.debug(
					"No increment data for sniffer={}, source={} and log={} found, create an empty one",
					sniffer, source, log);
			return new IncrementData();
		} else {
			return idatas.get(0);
		}
	}

	@Override
	public Map<Log, IncrementData> getIncrementDataByLog(final Sniffer sniffer,
			final LogSource<? extends LogInputStream> source)
			throws IOException {
		List<Log> logs = source.getLogs();
		if (logs.size() > 0) {
			final HashMap<Log, IncrementData> incs = new HashMap<Log, IncrementData>();
			final HashMap<String, Log> logMapping = new HashMap<String, Log>();
			for (Log log : logs) {
				logMapping.put(log.getPath(), log);
			}
			jdbcTemplate
					.query("SELECT NEXT_POINTER, DATA, LOG FROM SNIFFERS_SCANNER_IDATA WHERE SNIFFER=? AND SOURCE=? AND LOG IN ("
							+ StringUtils.repeat("?", ",", logs.size())
							+ ") ORDER BY LOG", ArrayUtils.addAll(new Object[] {
							sniffer.getId(), source.getId() }, logMapping
							.keySet().toArray(new Object[logMapping.size()])),
							new RowCallbackHandler() {
								@Override
								public void processRow(final ResultSet rs)
										throws SQLException {
									String logPath = rs.getString("LOG");
									Log log = logMapping.get(logPath);
									if (log != null) {
										IncrementData data = new IncrementData();
										data.setData(JSONObject.fromObject(rs
												.getString("DATA")));
										try {
											data.setNextOffset(source
													.getLogAccess(log)
													.getFromJSON(
															rs.getString("NEXT_POINTER")));
											incs.put(log, data);
										} catch (IOException e) {
											throw new SQLException(
													"Failed to construct pointer in log: "
															+ log, e);
										}
									} else {
										logger.error(
												"Didn't find log '{}' for selected incrementdata",
												logPath);
									}
								}
							});
			// Create empty entries for not yet persisted
			for (Log log : logMapping.values()) {
				if (!incs.containsKey(log)) {
					incs.put(log, new IncrementData());
				}
			}
			return incs;
		} else {
			return Collections.emptyMap();
		}
	}

	@Override
	public void storeIncrementalData(final Sniffer observer,
			final LogSource<? extends LogInputStream> source, final Log log,
			final IncrementData data) {
		ArrayList<Object> args = new ArrayList<Object>();
		args.add(data.getNextOffset().getJson());
		args.add(data.getData().toString());
		args.add(observer.getId());
		args.add(source.getId());
		args.add(log.getPath());
		Object[] a = args.toArray(new Object[args.size()]);
		if (jdbcTemplate
				.update("UPDATE SNIFFERS_SCANNER_IDATA SET NEXT_POINTER=?, DATA=? WHERE SNIFFER=? AND SOURCE=? AND LOG=?",
						a) == 0) {
			logger.debug(
					"Inc data for sniffer={}, source={} and log={} doesn't exists, create it",
					observer, source, log);
			jdbcTemplate
					.update("INSERT INTO SNIFFERS_SCANNER_IDATA SET NEXT_POINTER=?, DATA=?, SNIFFER=?, SOURCE=?, LOG=?",
							a);
		}
	}

	@Override
	public long createSniffer(final Sniffer sniffer) {
		GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(new SnifferCreator(true, sniffer), keyHolder);
		long snifferId = keyHolder.getKey().longValue();
		sniffer.setId(snifferId);
		logger.debug("Persisted new sniffer with id {}", snifferId);
		return snifferId;
	}

	@Override
	public void updateSniffer(final Sniffer sniffer) {
		jdbcTemplate.update(new SnifferCreator(false, sniffer));
		logger.debug("Updated sniffer {}", sniffer.getId());
	}

	@Override
	@Transactional
	public void deleteSniffer(final Sniffer sniffer) {
		jdbcTemplate.update(
				"DELETE FROM SNIFFERS_SCHEDULE_INFO WHERE SNIFFER=?",
				sniffer.getId());
		jdbcTemplate.update(
				"DELETE FROM SNIFFERS_SCANNER_IDATA WHERE SNIFFER=?",
				sniffer.getId());
		jdbcTemplate.update("DELETE FROM SNIFFERS_EVENTS WHERE SNIFFER=?",
				sniffer.getId());
		jdbcTemplate.update("DELETE FROM SNIFFERS WHERE ID=?", sniffer.getId());
		logger.info("Deleted sniffer for id: {}", sniffer.getId());
	}

}
