/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.ql.exec.FilterOperator;
import org.apache.hadoop.hive.ql.exec.TableScanOperator;
import org.apache.hadoop.hive.ql.lib.DefaultGraphWalker;
import org.apache.hadoop.hive.ql.lib.DefaultRuleDispatcher;
import org.apache.hadoop.hive.ql.lib.SemanticDispatcher;
import org.apache.hadoop.hive.ql.lib.SemanticGraphWalker;
import org.apache.hadoop.hive.ql.lib.Node;
import org.apache.hadoop.hive.ql.lib.SemanticNodeProcessor;
import org.apache.hadoop.hive.ql.lib.NodeProcessorCtx;
import org.apache.hadoop.hive.ql.lib.SemanticRule;
import org.apache.hadoop.hive.ql.lib.RuleExactMatch;
import org.apache.hadoop.hive.ql.lib.TypeRule;
import org.apache.hadoop.hive.ql.parse.ParseContext;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.plan.ExprNodeColumnDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeFieldDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeGenericFuncDesc;

/**
 * General utility common functions for the Pruner to do optimization.
 *
 */
public final class PrunerUtils {
  private PrunerUtils() {
    //prevent instantiation
  }

  /**
   * Walk operator tree for pruner generation.
   *
   * @param pctx
   * @param opWalkerCtx
   * @param filterProc
   * @param defaultProc
   * @throws SemanticException
   */
  public static void walkOperatorTree(ParseContext pctx, NodeProcessorCtx opWalkerCtx,
                                      SemanticNodeProcessor filterProc, SemanticNodeProcessor defaultProc) throws SemanticException {
    Map<SemanticRule, SemanticNodeProcessor> opRules = new LinkedHashMap<SemanticRule, SemanticNodeProcessor>();

    // Build regular expression for operator rule.
    // "(TS%FIL%)|(TS%FIL%FIL%)"
    String tsOprName = TableScanOperator.getOperatorName();
    String filtOprName = FilterOperator.getOperatorName();

    opRules.put(new RuleExactMatch("R1", new String[] {tsOprName, filtOprName, filtOprName}), filterProc);
    opRules.put(new RuleExactMatch("R2", new String[] {tsOprName, filtOprName}), filterProc);

    // The dispatcher fires the processor corresponding to the closest matching
    // rule and passes the context along
    SemanticDispatcher disp = new DefaultRuleDispatcher(defaultProc, opRules, opWalkerCtx);
    SemanticGraphWalker ogw = new DefaultGraphWalker(disp);

    // Create a list of topop nodes
    ArrayList<Node> topNodes = new ArrayList<Node>();
    topNodes.addAll(pctx.getTopOps().values());
    ogw.startWalking(topNodes, null);
  }

  /**
   * Walk expression tree for pruner generation.
   *
   * @param pred
   * @param ctx
   * @param colProc
   * @param fieldProc
   * @param genFuncProc
   * @param defProc
   * @return
   * @throws SemanticException
   */
  public static Map<Node, Object> walkExprTree(ExprNodeDesc pred, NodeProcessorCtx ctx,
                                               SemanticNodeProcessor colProc, SemanticNodeProcessor fieldProc, SemanticNodeProcessor genFuncProc,
                                               SemanticNodeProcessor defProc)
      throws SemanticException {
    // create a walker which walks the tree in a DFS manner while maintaining
    // the operator stack. The dispatcher
    // generates the plan from the operator tree
    Map<SemanticRule, SemanticNodeProcessor> exprRules = new LinkedHashMap<SemanticRule, SemanticNodeProcessor>();
    exprRules.put(new TypeRule(ExprNodeColumnDesc.class) , colProc);
    exprRules.put(new TypeRule(ExprNodeFieldDesc.class), fieldProc);
    exprRules.put(new TypeRule(ExprNodeGenericFuncDesc.class), genFuncProc);

    // The dispatcher fires the processor corresponding to the closest matching
    // rule and passes the context along
    SemanticDispatcher disp = new DefaultRuleDispatcher(defProc, exprRules, ctx);
    SemanticGraphWalker egw = new DefaultGraphWalker(disp);

    List<Node> startNodes = new ArrayList<Node>();
    startNodes.add(pred);

    HashMap<Node, Object> outputMap = new HashMap<Node, Object>();
    egw.startWalking(startNodes, outputMap);
    return outputMap;
  }

}
