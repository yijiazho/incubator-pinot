/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.query.reduce;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.pinot.common.config.TableNameBuilder;
import org.apache.pinot.common.exception.QueryException;
import org.apache.pinot.common.metrics.BrokerMeter;
import org.apache.pinot.common.metrics.BrokerMetrics;
import org.apache.pinot.common.request.BrokerRequest;
import org.apache.pinot.common.request.Selection;
import org.apache.pinot.common.response.broker.BrokerResponseNative;
import org.apache.pinot.common.response.broker.QueryProcessingException;
import org.apache.pinot.common.response.broker.ResultTable;
import org.apache.pinot.common.response.broker.SelectionResults;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.common.utils.DataTable;
import org.apache.pinot.core.query.selection.SelectionOperatorService;
import org.apache.pinot.core.query.selection.SelectionOperatorUtils;
import org.apache.pinot.core.transport.ServerRoutingInstance;
import org.apache.pinot.core.util.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Helper class to reduce and set Selection results into the BrokerResponseNative
 */
public class SelectionDataTableReducer implements DataTableReducer {

  private static final Logger LOGGER = LoggerFactory.getLogger(SelectionDataTableReducer.class);

  private final Selection _selection;
  private boolean _preserveType;
  private boolean _responseFormatSql;

  SelectionDataTableReducer(BrokerRequest brokerRequest, QueryOptions queryOptions) {
    _selection = brokerRequest.getSelections();
    _preserveType = queryOptions.isPreserveType();
    _responseFormatSql = queryOptions.isResponseFormatSQL();
  }

  /**
   * Reduces data tables and sets selection results into
   * 1. ResultTable if _responseFormatSql is true
   * 2. SelectionResults by default
   */
  @Override
  public void reduceAndSetResults(String tableName, DataSchema dataSchema,
      Map<ServerRoutingInstance, DataTable> dataTableMap, BrokerResponseNative brokerResponseNative,
      BrokerMetrics brokerMetrics) {
    Collection<DataTable> dataTables = dataTableMap.values();

    if (dataTableMap.isEmpty()) {
      // For empty data table map, construct empty result using the cached data schema for selection query if exists
      if (dataSchema != null) {
        List<String> selectionColumns =
            SelectionOperatorUtils.getSelectionColumns(_selection.getSelectionColumns(), dataSchema);
        if (_responseFormatSql) {
          DataSchema selectionDataSchema =
              SelectionOperatorUtils.getResultTableDataSchema(dataSchema, selectionColumns);
          brokerResponseNative.setResultTable(new ResultTable(selectionDataSchema, Collections.emptyList()));
        } else {
          brokerResponseNative.setSelectionResults(new SelectionResults(selectionColumns, Collections.emptyList()));
        }
      }
      return;
    } else {

      assert dataSchema != null;

      // For data table map with more than one data tables, remove conflicting data tables
      if (dataTableMap.size() > 1) {
        List<ServerRoutingInstance> droppedServers = removeConflictingResponses(dataSchema, dataTableMap);
        if (!droppedServers.isEmpty()) {
          String errorMessage = QueryException.MERGE_RESPONSE_ERROR.getMessage() + ": responses for table: " + tableName
              + " from servers: " + droppedServers + " got dropped due to data schema inconsistency.";
          LOGGER.warn(errorMessage);
          if (brokerMetrics != null) {
            brokerMetrics.addMeteredTableValue(TableNameBuilder.extractRawTableName(tableName),
                BrokerMeter.RESPONSE_MERGE_EXCEPTIONS, 1L);
          }
          brokerResponseNative
              .addToExceptions(new QueryProcessingException(QueryException.MERGE_RESPONSE_ERROR_CODE, errorMessage));
        }
      }

      int selectionSize = _selection.getSize();
      if (selectionSize > 0 && _selection.isSetSelectionSortSequence()) {
        // Selection order-by
        SelectionOperatorService selectionService = new SelectionOperatorService(_selection, dataSchema);
        selectionService.reduceWithOrdering(dataTables);
        if (_responseFormatSql) {
          // TODO: Selection uses Serializable[] in all its operations
          //   Converting that to Object[] end to end would be a big change, and will be done in future PRs
          brokerResponseNative.setResultTable(selectionService.renderResultTableWithOrdering());
        } else {
          brokerResponseNative.setSelectionResults(selectionService.renderSelectionResultsWithOrdering(_preserveType));
        }
      } else {
        // Selection only
        List<String> selectionColumns =
            SelectionOperatorUtils.getSelectionColumns(_selection.getSelectionColumns(), dataSchema);
        List<Serializable[]> reducedRows = SelectionOperatorUtils.reduceWithoutOrdering(dataTables, selectionSize);
        if (_responseFormatSql) {
          // TODO: Selection uses Serializable[] in all its operations
          //   Converting that to Object[] end to end would be a big change, and will be done in future PRs
          brokerResponseNative
              .setResultTable(SelectionOperatorUtils.renderResultTableWithoutOrdering(reducedRows, dataSchema));
        } else {
          brokerResponseNative.setSelectionResults(SelectionOperatorUtils
              .renderSelectionResultsWithoutOrdering(reducedRows, dataSchema, selectionColumns, _preserveType));
        }
      }
    }
  }

  /**
   * Given a data schema, remove data tables that are not compatible with this data schema.
   * <p>Upgrade the data schema passed in to cover all remaining data schemas.
   *
   * @param dataSchema data schema.
   * @param dataTableMap map from server to data table.
   * @return list of server names where the data table got removed.
   */
  private List<ServerRoutingInstance> removeConflictingResponses(DataSchema dataSchema,
      Map<ServerRoutingInstance, DataTable> dataTableMap) {
    List<ServerRoutingInstance> droppedServers = new ArrayList<>();
    Iterator<Map.Entry<ServerRoutingInstance, DataTable>> iterator = dataTableMap.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<ServerRoutingInstance, DataTable> entry = iterator.next();
      DataSchema dataSchemaToCompare = entry.getValue().getDataSchema();
      assert dataSchemaToCompare != null;
      if (!dataSchema.isTypeCompatibleWith(dataSchemaToCompare)) {
        droppedServers.add(entry.getKey());
        iterator.remove();
      } else {
        dataSchema.upgradeToCover(dataSchemaToCompare);
      }
    }
    return droppedServers;
  }
}
