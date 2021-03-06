/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  See the NOTICE file distributed with this work for additional
 *  information regarding copyright ownership.
 */

package org.spinrdf.arq.functions;

import java.util.LinkedList;
import java.util.List;

import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.sparql.core.Substitute;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingHashMap;
import org.apache.jena.sparql.engine.binding.BindingMap;
import org.apache.jena.sparql.engine.iterator.QueryIterConcat;
import org.apache.jena.sparql.expr.ExprEvalException;
import org.apache.jena.sparql.pfunction.PropFuncArg;
import org.apache.jena.sparql.pfunction.PropertyFunctionBase;
import org.apache.jena.sparql.util.IterLib;
import org.spinrdf.constraints.ConstraintViolation;
import org.spinrdf.constraints.SPINConstraints;
import org.spinrdf.model.QueryOrTemplateCall;
import org.spinrdf.progress.NullProgressMonitor;
import org.spinrdf.progress.ProgressMonitor;
import org.spinrdf.util.JenaUtil;
import org.spinrdf.util.SPINUtil;
import org.spinrdf.vocabulary.SPIN;

/**
 * The magic property spin:constructViolations.
 */
public class ConstructViolationsPFunction extends PropertyFunctionBase {
	
	private final static String NAME = SPIN.PREFIX + ":" + SPIN.constructViolations.getLocalName();

	@Override
	public QueryIterator exec(Binding binding, PropFuncArg argSubject,
			Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {

		argSubject = Substitute.substitute(argSubject, binding);
		argObject = Substitute.substitute(argObject, binding);

		List<Node> objects = SPINFunctionUtil.getNodes(argObject);
		if(objects.size() != 3) {
			throw new ExprEvalException(NAME + " must have three nodes on the right side");
		}
		if(!objects.get(0).isVariable() || !objects.get(1).isVariable() || !objects.get(2).isVariable()) {
			throw new ExprEvalException(NAME + " must have three unbound variables on the right side");
		}
		
		List<Node> subjects = SPINFunctionUtil.getNodes(argSubject);
		if(subjects.size() != 2) {
			throw new ExprEvalException(NAME + " must have two nodes on the left side");
		}

		Node instanceNode = subjects.get(0);
		if(!instanceNode.isURI() && !instanceNode.isBlank()) {
			throw new ExprEvalException(NAME + " must have a resource as its first argument on the left side");
		}

		Node classNode = subjects.get(1);
		if(!classNode.isURI() && !classNode.isBlank()) {
			throw new ExprEvalException(NAME + " must have a resource as its second argument on the left side");
		}
		
		Model model = ModelFactory.createModelForGraph(execCxt.getActiveGraph());
		
		// Collect all constraints defined at the class and its superclasses
		Resource cls = (Resource)model.asRDFNode(classNode);
		
		List<QueryOrTemplateCall> qots = getConstraints(cls);

		Resource instance = (Resource) model.asRDFNode(instanceNode);
		ProgressMonitor monitor = new NullProgressMonitor();
		List<ConstraintViolation> results = new LinkedList<ConstraintViolation>();
		for(QueryOrTemplateCall qot : qots) {
			if(qot.getTemplateCall() != null) {
				SPINConstraints.addTemplateCallResults(results, qot, instance, false, monitor);
			}
			else if(qot.getQuery() != null) {
				SPINConstraints.addQueryResults(results, qot, instance, false, null, monitor);
			}
		}
		Model cvModel = JenaUtil.createMemoryModel();
		SPINConstraints.addConstraintViolationsRDF(results, cvModel, true);

		QueryIterConcat concat = new QueryIterConcat(execCxt);
		for(Statement s : cvModel.listStatements().toList()) {
			BindingMap bindingMap = new BindingHashMap(binding);
			bindingMap.add((Var)objects.get(0), s.getSubject().asNode());
			bindingMap.add((Var)objects.get(1), s.getPredicate().asNode());
			bindingMap.add((Var)objects.get(2), s.getObject().asNode());
			concat.add(IterLib.result(bindingMap, execCxt));
		}
		
		return concat;
	}

	
	static List<QueryOrTemplateCall> getConstraints(Resource cls) {
		List<QueryOrTemplateCall> qots = new LinkedList<QueryOrTemplateCall>();
		Property constraintProperty = cls.getModel().getProperty(SPIN.constraint.getURI());
		SPINUtil.addQueryOrTemplateCalls(cls, constraintProperty, qots);
		for(Resource superClass : JenaUtil.getAllSuperClasses(cls)) {
			SPINUtil.addQueryOrTemplateCalls(superClass, constraintProperty, qots);
		}
		return qots;
	}
}
