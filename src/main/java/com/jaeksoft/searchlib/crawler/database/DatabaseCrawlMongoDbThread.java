/*
 * License Agreement for OpenSearchServer
 * <p>
 * Copyright (C) 2015-2017 Emmanuel Keller / Jaeksoft
 * <p>
 * http://www.open-search-server.com
 * <p>
 * This file is part of OpenSearchServer.
 * <p>
 * OpenSearchServer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * OpenSearchServer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with OpenSearchServer.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.jaeksoft.searchlib.crawler.database;

import com.jaeksoft.searchlib.Client;
import com.jaeksoft.searchlib.SearchLibException;
import com.jaeksoft.searchlib.analysis.LanguageEnum;
import com.jaeksoft.searchlib.crawler.FieldMapContext;
import com.jaeksoft.searchlib.crawler.common.process.CrawlStatus;
import com.jaeksoft.searchlib.crawler.rest.RestFieldMap;
import com.jaeksoft.searchlib.function.expression.SyntaxError;
import com.jaeksoft.searchlib.index.IndexDocument;
import com.jaeksoft.searchlib.query.ParseException;
import com.jaeksoft.searchlib.util.InfoCallback;
import com.jaeksoft.searchlib.util.ReadWriteLock;
import com.jaeksoft.searchlib.util.Variables;
import com.jayway.jsonpath.Configuration;
import com.mongodb.MongoClient;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.util.JSON;
import org.bson.Document;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public class DatabaseCrawlMongoDbThread extends DatabaseCrawlThread {

	private final ReadWriteLock rwl = new ReadWriteLock();

	private final DatabaseCrawlMongoDb databaseCrawl;

	public DatabaseCrawlMongoDbThread(Client client, DatabaseCrawlMaster crawlMaster,
			DatabaseCrawlMongoDb databaseCrawl, Variables variables, InfoCallback infoCallback) {
		super(client, crawlMaster, databaseCrawl, infoCallback);
		this.databaseCrawl = (DatabaseCrawlMongoDb) databaseCrawl.duplicate();
		this.databaseCrawl.applyVariables(variables);
	}

	private void runnerUpdate(FindIterable<Document> iterable)
			throws SearchLibException, ClassNotFoundException, InstantiationException, IllegalAccessException,
			IOException, ParseException, SyntaxError, URISyntaxException, InterruptedException {
		final int limit = databaseCrawl.getBufferSize();
		iterable.batchSize(limit);
		final RestFieldMap fieldMap = (RestFieldMap) databaseCrawl.getFieldMap();
		List<IndexDocument> indexDocumentList = new ArrayList<IndexDocument>(0);
		LanguageEnum lang = databaseCrawl.getLang();
		FieldMapContext fieldMapContext = new FieldMapContext(client, lang);
		String uniqueField = client.getSchema().getUniqueField();
		MongoCursor<Document> cursor = iterable.iterator();
		while (cursor.hasNext() && !isAborted()) {

			String json = JSON.serialize(cursor.next());
			Object document = Configuration.defaultConfiguration().jsonProvider().parse(json);
			IndexDocument indexDocument = new IndexDocument(lang);
			fieldMap.mapJson(fieldMapContext, document, indexDocument);
			if (uniqueField != null && !indexDocument.hasContent(uniqueField)) {
				rwl.w.lock();
				try {
					ignoredDocumentCount++;
				} finally {
					rwl.w.unlock();
				}
				continue;
			}
			indexDocumentList.add(indexDocument);
			rwl.w.lock();
			try {
				pendingIndexDocumentCount++;
			} finally {
				rwl.w.unlock();
			}
			if (index(indexDocumentList, limit))
				setStatus(CrawlStatus.CRAWL);

		}
		index(indexDocumentList, 0);
	}

	@Override
	public void runner() throws Exception {
		setStatus(CrawlStatus.STARTING);

		try (final MongoClient mongoClient = databaseCrawl.getMongoClient()) {
			final MongoCollection<Document> collection = databaseCrawl.getCollection(mongoClient);
			final FindIterable<Document> iterable = collection.find(databaseCrawl.getCriteriaObject());
			setStatus(CrawlStatus.CRAWL);
			if (iterable != null)
				runnerUpdate(iterable);
			if (updatedIndexDocumentCount > 0 || updatedDeleteDocumentCount > 0)
				client.reload();
		}

	}
}
