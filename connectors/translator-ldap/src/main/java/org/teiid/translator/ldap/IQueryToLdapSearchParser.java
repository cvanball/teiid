/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

/**
 * Utility class to handle the parsing of an IQuery object into all the relevant LDAP search
 * information. Uses LdapSearchDetails class to store this information and return the search details.
 * This class was intended for use by the execution classes that need to translate SQL. As new capabilities
 * are implemented, this class will be expanded to accommodate the appropriate SQL.
 * 
 * This class should remove all the MMX-specific stuff, and turn it into something any
 * LDAP implementation can understand.
 *
 */

package org.teiid.translator.ldap;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.StringTokenizer;

import javax.naming.directory.SearchControls;
import javax.naming.ldap.SortKey;

import org.teiid.core.util.StringUtil;
import org.teiid.language.*;
import org.teiid.language.Comparison.Operator;
import org.teiid.language.SortSpecification.Ordering;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.metadata.Column;
import org.teiid.metadata.Datatype;
import org.teiid.metadata.Table;
import org.teiid.translator.TranslatorException;
import org.teiid.translator.TypeFacility;
import org.teiid.translator.ldap.LDAPExecutionFactory.SearchDefaultScope;



/**
 * Utility class which translates a SQL query into an LDAP search.
 */
public class IQueryToLdapSearchParser {
	private static final String ATTRIBUTES = "attributes"; //$NON-NLS-1$
	private static final String COUNT_LIMIT = "count-limit"; //$NON-NLS-1$
	private static final String TIMEOUT = "timeout";//$NON-NLS-1$
	private static final String SEARCH_SCOPE = "search-scope";//$NON-NLS-1$
	private static final String CRITERIA = "filter";//$NON-NLS-1$
	private static final String CONTEXT_NAME = "context-name";//$NON-NLS-1$	
	LDAPExecutionFactory executionFactory;
	
	/**
	 * Constructor.
	 */
	public IQueryToLdapSearchParser(LDAPExecutionFactory factory) {
		this.executionFactory = factory;
	}
	
	/** 
	 * Public entry point to the parser.
	 *  Parses the IQuery object, and constructs an equivalent LDAP search filter,
	 *  keeping track of the attributes of interest.
	 *  Here are some example SQL queries, and the equivalent LDAP search info:
	 *  SQL: select cn, managerName from people_table where managerName LIKE "John%" and cn!="Mar()"
	 *  Context name: [people_table's NameInSource, e.g. (ou=people,dc=company,dc=com)]
	 *  LDAP attributes: (cn, String), (managerName, String)
	 *  LDAP search filter: (&(managerName="John*")(!(cn="Mar\(\)")))
	 *  
	 *  @param query the query
	 *  @return the LDAPSearchDetails object
	 */
	// GHH 20080326 - added ability to restrict queries to only values where
	// objectClass = table name.  This is done by adding a third parameter,
	// RESTRICT, to the NameInSource property in the model:
	// ou=people,dc=company,dc=com?SUBTREE_SCOPE?RESTRICT
	// TODO - change method for calling RESTRICT to also specify
	// object class name (RESTRICT=inetOrgPerson)
	public LDAPSearchDetails translateSQLQueryToLDAPSearch(Select query) throws TranslatorException {
		// Parse SELECT symbols.
		// The columns will be translated into LDAP attributes of interest.
		ArrayList<Column> elementList = getElementsFromSelectSymbols(query);
		
		// Parse FROM table.
		// Only one table is expected here.
		List<TableReference> fromList = query.getFrom();
		Iterator<TableReference> itr = fromList.iterator();
		if(!itr.hasNext()) {
            final String msg = LDAPPlugin.Util.getString("IQueryToLdapSearchParser.noTablesInFromError"); //$NON-NLS-1$
			throw new TranslatorException(msg); 
		}
		TableReference fItm = itr.next();
		if(itr.hasNext()) {
            final String msg = LDAPPlugin.Util.getString("IQueryToLdapSearchParser.multiItemsInFromError"); //$NON-NLS-1$
			throw new TranslatorException(msg); 
		}
		
		LDAPSearchDetails sd = null;
		String contextName = getContextNameFromFromItem(fItm);
		int searchScope = getSearchScopeFromFromItem(fItm);
		// GHH 20080326 - added check for RESTRICT parameter in
		// NameInSource of from item
		String classRestriction = getRestrictToNamedClass(fItm);
				
		// Parse the WHERE clause.
		// Create an equivalent LDAP search filter.
		List<String> searchStringList = new LinkedList<String>();
		searchStringList = getSearchFilterFromWhereClause(query.getWhere(), searchStringList);
		StringBuilder filterBuilder = new StringBuilder();
		for (String string : searchStringList) {
			filterBuilder.append(string);
		}
		// GHH 20080326 - if there is a class restriction,
		// add it to the search filter
		if (classRestriction != null && classRestriction.trim().length()>0) {
			filterBuilder.insert(0, "(&").append("(objectClass=").append(classRestriction).append("))");  //$NON-NLS-1$  //$NON-NLS-2$  //$NON-NLS-3$
		}
		
		// Parse the ORDER BY clause.
		// Create an ordered sort list.
		OrderBy orderBy = query.getOrderBy();
		// Referenced the JNDI standard...arguably, this should not be done inside this
		// class, and we should make our own key class. In practice, this makes things simpler.
		SortKey[] sortKeys = getSortKeysFromOrderByClause(orderBy);
		
		// Parse LIMIT clause. 
		// Note that offsets are not supported.
		Limit limit = query.getLimit();
		long countLimit = -1;
		if(limit != null) {
			countLimit = limit.getRowLimit();
		}
		
		// Create Search Details
		sd = new LDAPSearchDetails(contextName, searchScope, filterBuilder.toString(), sortKeys, countLimit, elementList, 0);
		// Search Details logging	    	
		sd.printDetailsToLog();
		return sd;
			
	}
	
	/**
	 * get SortKeys from the supplied ORDERBY clause.
	 * @param orderBy the OrderBy clause
	 * @param the array of SortKeys
	 */
	private SortKey[] getSortKeysFromOrderByClause(OrderBy orderBy) throws TranslatorException {
		SortKey[] sortKeys = null;
		if(orderBy != null) {
			List<SortSpecification> orderItems = orderBy.getSortSpecifications();
			if(orderItems == null) {
				return null;
			}
			SortKey sortKey = null;
			sortKeys = new SortKey[orderItems.size()];
			Iterator<SortSpecification> orderItr = orderItems.iterator();
			int i = 0;
			while(orderItr.hasNext()) {
				SortSpecification item = orderItr.next();
				String itemName = getExpressionString(item.getExpression());
				LogManager.logTrace(LogConstants.CTX_CONNECTOR, "Adding sort key for item: " + itemName); //$NON-NLS-1$
				if(item.getOrdering() == Ordering.ASC) {
					LogManager.logTrace(LogConstants.CTX_CONNECTOR, "with ASC ordering."); //$NON-NLS-1$
					sortKey = new SortKey(itemName, true, null);
				} else if(item.getOrdering() == Ordering.DESC){
					LogManager.logTrace(LogConstants.CTX_CONNECTOR, "with DESC ordering."); //$NON-NLS-1$
					sortKey = new SortKey(itemName, false, null);
				}
				sortKeys[i] = sortKey;
				i++;
			}
			
		} else {
			// Insert a default? No, allow the Execution to do this. Just return a null list.
		}
		return sortKeys;
	}
	
	/** 
	 * Utility method to pull the name in source (or, base DN/context name) from the table.
	 * @param fromItem
	 * @return name in source
	 */
	// GHH 20080409 - changed to fall back on new connector property
	// for default base DN if available
	private String getContextNameFromFromItem(TableReference fromItem) throws TranslatorException {
		String nameInSource;
		String contextName;

		// TODO: Re-use the getExpressionString method if possible, rather than 
		// rewriting the same code twice.
		if(fromItem instanceof NamedTable) {
			Table group = ((NamedTable)fromItem).getMetadataObject();
			nameInSource = group.getNameInSource();
			// if NameInSource is null set it to an empty
			// string instead so we can safely call split on it
			if(nameInSource == null) {
				nameInSource = "";  //$NON-NLS-1$
			}
			// now split it on ? to find the part of it that specifies context name
			String nameInSourceArray[] = nameInSource.split("\\?");  //$NON-NLS-1$
			contextName = nameInSourceArray[0];
			// if there is no context name specified
			// try the default in the connector properties
			if(contextName.equals("")) {  //$NON-NLS-1$
				contextName = this.executionFactory.getSearchDefaultBaseDN();
			}
		} else {
            final String msg = LDAPPlugin.Util.getString("IQueryToLdapSearchParser.groupCountExceededError"); //$NON-NLS-1$
			throw new TranslatorException(msg);
		}
		// if the context name is not specified either in Name in Source
		// or in the default connector properties it'll be either
		// null or an empty string
		if(contextName == null || contextName.equals("")) { //$NON-NLS-1$
            final String msg = LDAPPlugin.Util.getString("IQueryToLdapSearchParser.baseContextNameError"); //$NON-NLS-1$
			throw new TranslatorException(msg);
		}
		return contextName;

	}

	// GHH 20080326 - added below method to check for RESTRICT parameter in
	// from item's NameInSource, and if true return name (not NameInSource)
	// of from item as the objectClass name on to which the query should
	// be restricted
	private String getRestrictToNamedClass(TableReference fromItem) throws TranslatorException {
		String nameInSource;
		String namedClass = null;
		if(fromItem instanceof NamedTable) {
			// Here we use slightly different logic than in
			// getContextNameFromFromItem so it is easier to get
			// the group name later if needed
			Table mdIDGroup = ((NamedTable)fromItem).getMetadataObject();
			nameInSource = mdIDGroup.getNameInSource();
			// groupName = mdIDGroup.getName();
			// if NameInSource is null set it to an empty
			// string instead so we can safely call split on it
			if(nameInSource == null) {
				nameInSource = "";  //$NON-NLS-1$
			}
			// now split it on ? to find the part of it that specifies the objectClass we should restrict on
			String nameInSourceArray[] = nameInSource.split("\\?");  //$NON-NLS-1$
			if(nameInSourceArray.length >= 3) {
				namedClass = nameInSourceArray[2];
			}
			// if there is no specification in the Name In Source,
			// see if the connector property is set to true.  If
			// it is, use the Name of the class for the restriction.
			if(namedClass == null || namedClass.equals("")) {  //$NON-NLS-1$
				if (!this.executionFactory.isRestrictToObjectClass()) { 
					namedClass = "";  //$NON-NLS-1$
				} else {
					namedClass = mdIDGroup.getName();
				}
			}
		} else {
            final String msg = LDAPPlugin.Util.getString("IQueryToLdapSearchParser.groupCountExceededError"); //$NON-NLS-1$
			throw new TranslatorException(msg);
		}
		return namedClass;
	}

	private int getSearchScopeFromFromItem(TableReference fromItem) throws TranslatorException {
		String searchScopeString = "";  //$NON-NLS-1$
		int searchScope = LDAPConnectorConstants.ldapDefaultSearchScope;
		// TODO: Re-use the getExpressionString method if possible, rather than 
		// rewriting the same code twice.
		if(fromItem instanceof NamedTable) {
			Table group = ((NamedTable)fromItem).getMetadataObject();
			String nameInSource = group.getNameInSource();
			// if NameInSource is null set it to an empty
			// string instead so we can safely call split on it
			if(nameInSource == null) {
				nameInSource = "";  //$NON-NLS-1$
			}
			// now split it on ? to find the part of it that specifies search scope
			String nameInSourceArray[] = nameInSource.split("\\?");  //$NON-NLS-1$
			if(nameInSourceArray.length >= 2) {
				searchScopeString = nameInSourceArray[1];
			}
			// if there is no search scope specified
			// try the default in the connector properties
			if(searchScopeString.equals("")) {  //$NON-NLS-1$
				SearchDefaultScope searchDefaultScope = this.executionFactory.getSearchDefaultScope();
				if (searchDefaultScope != null) {
					searchScopeString = searchDefaultScope.name();
				}
				// protect against getting null back from the property
				if(searchScopeString == null) {
					searchScopeString = "";  //$NON-NLS-1$
				}
			}
			if(searchScopeString.equals("SUBTREE_SCOPE")) {  //$NON-NLS-1$
				searchScope = SearchControls.SUBTREE_SCOPE;
			} else if(searchScopeString.equals("ONELEVEL_SCOPE")) {  //$NON-NLS-1$
				searchScope = SearchControls.ONELEVEL_SCOPE;
			} else if(searchScopeString.equals("OBJECT_SCOPE")) {  //$NON-NLS-1$
				searchScope = SearchControls.OBJECT_SCOPE;
			}
		} else {
            final String msg = LDAPPlugin.Util.getString("IQueryToLdapSearchParser.groupCountExceededError"); //$NON-NLS-1$
			throw new TranslatorException(msg);
		}
		return searchScope;
	}
	
	/** 
	 * Utility method to convert operator to the appropriate string value for LDAP.
	 * @param op operator to evaluate
	 * @return LDAP-specific string equivalent of the operator
	 */
	private String parseCompoundCriteriaOp(AndOr.Operator op) throws TranslatorException {
		switch(op) {
			case AND:
				return "&";	 //$NON-NLS-1$
			case OR:
				return "|"; //$NON-NLS-1$
			default:
	            final String msg = LDAPPlugin.Util.getString("IQueryToLdapSearchParser.criteriaNotParsableError"); //$NON-NLS-1$
				throw new TranslatorException(msg); 
		}
	}

	/** 
	 * Utility method to convert expression to the appropriate string value for LDAP.
	 * @param e expression to evaluate
	 * @return LDAP-specific string equivalent of the expression
	 */
	// GHH 20080326 - found that code to fall back on Name if NameInSource
	// was null wasn't working properly, so replaced with tried and true
	// code from another custom connector.
	private String getExpressionString(Expression e) throws TranslatorException {
		String expressionName = null;
		// GHH 20080326 - changed around the IElement handling here
		// - the rest of this method is unchanged
		if(e instanceof ColumnReference) {
			Column mdIDElement = ((ColumnReference)e).getMetadataObject();
			expressionName = mdIDElement.getNameInSource();
			if(expressionName == null || expressionName.equals("")) {  //$NON-NLS-1$
				expressionName = mdIDElement.getName();
			}
		} else if(e instanceof Literal) {
			expressionName = getExpressionString((Literal)e);
		} else {
            final String msg = LDAPPlugin.Util.getString("IQueryToLdapSearchParser.unsupportedElementError", e.getClass().getSimpleName()); //$NON-NLS-1$
			throw new TranslatorException(msg + e.toString()); 
		}
		expressionName = escapeReservedChars(expressionName);
		return expressionName;
	}

	static String getExpressionString(Literal l) {
		if(l.getValue() instanceof Timestamp) {
			LogManager.logTrace(LogConstants.CTX_CONNECTOR, "Found an expression that uses timestamp; converting to LDAP string format."); //$NON-NLS-1$
			Timestamp ts = (Timestamp)l.getValue();
			Date dt = new Date(ts.getTime());
			//TODO: Fetch format if provided.
			SimpleDateFormat sdf = new SimpleDateFormat(LDAPConnectorConstants.ldapTimestampFormat);
			String expressionName = sdf.format(dt);
			LogManager.logTrace(LogConstants.CTX_CONNECTOR, "Timestamp to string is: " + expressionName); //$NON-NLS-1$
			return expressionName;
		}
		if (l.getValue() != null) {
			return l.getValue().toString();
		}
		return "null";  //$NON-NLS-1$
	}
	
	static String escapeReservedChars(String expr) {
		StringBuffer sb = new StringBuffer();
        for (int i = 0; i < expr.length(); i++) {
            char curChar = expr.charAt(i);
            switch (curChar) {
                case '\\':
                    sb.append("\\5c"); //$NON-NLS-1$
                    break;
                case '*':
                    sb.append("\\2a"); //$NON-NLS-1$
                    break;
                case '(':
                    sb.append("\\28"); //$NON-NLS-1$
                    break;
                case ')':
                    sb.append("\\29"); //$NON-NLS-1$
                    break;
                case '\u0000': 
                    sb.append("\\00"); //$NON-NLS-1$
                    break;
                default:
                    sb.append(curChar);
            }
        }
        return sb.toString();
	}

	/** 
	 * Recursive method to translate the where clause into an LDAP search filter.
	 * The goal is to convert infix notation to prefix (Polish) notation.
	 * TODO: There's probably a clever way to do this with a Visitor.
	 * @param criteria Criteria to evaluate.
	 * @param List list to hold each pre-fix character of the search filter. 
	 * @return list list that can be traversed in order to construct the search filter.
	 */
	private List<String> getSearchFilterFromWhereClause(Condition criteria, List<String> filterList) throws TranslatorException {
		if(criteria == null) {
			filterList.add("(objectClass=*)"); //$NON-NLS-1$
		}
		boolean isNegated = false;
		// Recursive case: compound criteria
		if(criteria instanceof AndOr) {
			AndOr crit = (AndOr)criteria;
			AndOr.Operator op = crit.getOperator();
			LogManager.logTrace(LogConstants.CTX_CONNECTOR, "Parsing compound criteria."); //$NON-NLS-1$
			String stringOp = parseCompoundCriteriaOp(op);
			
			filterList.add("("); //$NON-NLS-1$
			filterList.add(stringOp);
			filterList.addAll(getSearchFilterFromWhereClause(crit.getLeftCondition(), new LinkedList<String>()));
			filterList.addAll(getSearchFilterFromWhereClause(crit.getRightCondition(), new LinkedList<String>()));
			filterList.add(")"); //$NON-NLS-1$
		// Base case
		} else if(criteria instanceof Comparison) {
			LogManager.logTrace(LogConstants.CTX_CONNECTOR, "Parsing compare criteria."); //$NON-NLS-1$
			Comparison.Operator op = ((Comparison) criteria).getOperator();
			
			isNegated = op == Operator.NE || op == Operator.GT || op == Operator.LT;
			
			Expression lhs = ((Comparison) criteria).getLeftExpression();
			Expression rhs = ((Comparison) criteria).getRightExpression();
		
			String lhsString = getExpressionString(lhs);
			String rhsString = getExpressionString(rhs);
			if(lhsString == null || rhsString == null) {
	            final String msg = LDAPPlugin.Util.getString("IQueryToLdapSearchParser.missingNISError"); //$NON-NLS-1$
				throw new TranslatorException(msg); 
			}
			
			addCompareCriteriaToList(filterList, op, lhsString, rhsString);
		// Base case
		} else if(criteria instanceof Exists) {
			LogManager.logTrace(LogConstants.CTX_CONNECTOR, "Parsing EXISTS criteria: NOT IMPLEMENTED YET"); //$NON-NLS-1$
			// TODO Exists should be supported in a future release.
		// Base case
		} else if(criteria instanceof Like) {
			LogManager.logTrace(LogConstants.CTX_CONNECTOR, "Parsing LIKE criteria."); //$NON-NLS-1$
			isNegated = ((Like) criteria).isNegated();
			// Convert LIKE to Equals, where any "%" symbol is replaced with "*".
			Comparison.Operator op = Operator.EQ;
			Expression lhs = ((Like) criteria).getLeftExpression();
			Expression rhs = ((Like) criteria).getRightExpression();
		
			String lhsString = getExpressionString(lhs);
			String rhsString = getExpressionString(rhs);
			rhsString = rhsString.replace("%", "*"); //$NON-NLS-1$ //$NON-NLS-2$
			addCompareCriteriaToList(filterList, op, lhsString, rhsString);
			
		// Base case
		} else if(criteria instanceof In) {
			LogManager.logTrace(LogConstants.CTX_CONNECTOR, "Parsing IN criteria."); //$NON-NLS-1$
			isNegated = ((In) criteria).isNegated();
			Expression lhs = ((In)criteria).getLeftExpression();
			List<Expression> rhsList = ((In)criteria).getRightExpressions();
			// Recursively add each IN expression to the filter list.
			processInCriteriaList(filterList, rhsList, lhs);
		} else if (criteria instanceof Not) {
			LogManager.logTrace(LogConstants.CTX_CONNECTOR, "Parsing NOT criteria."); //$NON-NLS-1$
			isNegated = true;
			filterList.addAll(getSearchFilterFromWhereClause(((Not)criteria).getCriteria(), new LinkedList<String>()));
		}
		
		if (isNegated) {
			filterList.add(0, "("); //$NON-NLS-1$
			filterList.add(1, "!"); //$NON-NLS-1$
			filterList.add(")"); //$NON-NLS-1$
		}
		
		return filterList;
	}
	
	/** 
	 * Process a list of right-hand side IN expresssions and add the corresponding LDAP filter
	 * clause string to the given filterList.
	 */
	private void processInCriteriaList(List<String> filterList, List<Expression> rhsList, Expression lhs) throws TranslatorException {
		if(rhsList.size() == 0) {
			return;
		}
		filterList.add("("); //$NON-NLS-1$
		filterList.add(parseCompoundCriteriaOp(org.teiid.language.AndOr.Operator.OR));
		Iterator<Expression> rhsItr = rhsList.iterator();
		while(rhsItr.hasNext()) {
			addCompareCriteriaToList(filterList, Operator.EQ, getExpressionString(lhs), 
					getExpressionString(rhsItr.next()));
		}
		filterList.add(")"); //$NON-NLS-1$
	}
	
	/** 
	 * Add Compare Criteria to List
	 * @param filterList the filter list
	 * @param op
	 * @param lhs left hand side expression
	 * @param rhs right hand side expression
	 */
	private void addCompareCriteriaToList(List<String> filterList, Comparison.Operator op, String lhs, String rhs) throws TranslatorException {
		// Push the comparison statement into the list, e.g.:
		// (sn=Mike)
		// !(empNum>=100)
		filterList.add("("); //$NON-NLS-1$
		filterList.add(lhs);

		switch(op) {
		    case NE:
			case EQ:
				filterList.add("="); //$NON-NLS-1$
				break;
			case LT:
			case GE:
				filterList.add(">="); //$NON-NLS-1$
				break;
			case GT:
			case LE:
				filterList.add("<="); //$NON-NLS-1$
				break;
			default:
	            final String msg = LDAPPlugin.Util.getString("IQueryToLdapSearchParser.criteriaNotSupportedError"); //$NON-NLS-1$
				throw new TranslatorException(msg); 
				
		}
		filterList.add(rhs);
		filterList.add(")"); //$NON-NLS-1$
	}
	
	/** 
	 * Method to get name from the supplied Element
	 * @param e the supplied Element
	 * @return the name
	 */
    // GHH 20080326 - found that code to fall back on Name if NameInSource
	// was null wasn't working properly, so replaced with tried and true
	// code from another custom connector.
	public static String getNameFromElement(Column e) {
		String ldapAttributeName = null;
		ldapAttributeName = e.getNameInSource();
		if (ldapAttributeName == null || ldapAttributeName.equals("")) { //$NON-NLS-1$
			ldapAttributeName = e.getName();
			// If name in source is not set, then fall back to the column name.
		}
		return ldapAttributeName;
	}
	
	/** 
	 * Method to get SELECT Element list from the supplied query
	 * @param query the supplied Query
	 * @return the list of SELECT elements
	 */
	private ArrayList<Column> getElementsFromSelectSymbols(Select query) {
		ArrayList<Column> selectElementList = new ArrayList<Column>();
		Iterator<DerivedColumn> selectSymbolItr = query.getDerivedColumns().iterator();

		while(selectSymbolItr.hasNext()) {
			Column e = getElementFromSymbol(selectSymbolItr.next());
			selectElementList.add(e);
		}
		return selectElementList;
	}
	
	
    /**
     * Helper method for getting runtime {@link org.teiid.connector.metadata.runtime.Element} from a
     * {@link org.teiid.language.DerivedColumn}.
     * @param symbol Input ISelectSymbol
     * @return Element returned metadata runtime Element
     */
    private Column getElementFromSymbol(DerivedColumn symbol) {
        ColumnReference expr = (ColumnReference) symbol.getExpression();
        return expr.getMetadataObject();
    }
	
	public LDAPSearchDetails buildRequest(String query) throws TranslatorException {
		ArrayList<String> attributes = new ArrayList<String>();
		ArrayList<Column> columns = new ArrayList<Column>();
		String contextName = null;
		String criteria = ""; //$NON-NLS-1$
		String searchScope = this.executionFactory.getSearchDefaultScope().name();
		int timeLimit = 0;
		long countLimit = -1;
		List<String> parts = StringUtil.tokenize(query, ';');
		for (String var : parts) {
			int index = var.indexOf('=');
			if (index == -1) {
				throw new TranslatorException(LDAPPlugin.Util.gs(LDAPPlugin.Event.TEIID12013, var));
			}
			String key = var.substring(0, index).trim();
			String value = var.substring(index+1).trim();
			
			if (key.equalsIgnoreCase(CONTEXT_NAME)) {
				contextName = value;
			}
			else if (key.equalsIgnoreCase(CRITERIA)) {
				criteria = value;
			}
			else if (key.equalsIgnoreCase(SEARCH_SCOPE)) {
				searchScope = value;
			}
			else if (key.equalsIgnoreCase(TIMEOUT)) {
				timeLimit = Integer.parseInt(value);
			}
			else if (key.equalsIgnoreCase(COUNT_LIMIT)) {
				countLimit = Long.parseLong(value);
			}
			else if (key.equalsIgnoreCase(ATTRIBUTES)) {
				StringTokenizer attrTokens = new StringTokenizer(value, ","); //$NON-NLS-1$
				while(attrTokens.hasMoreElements()) {
					String name = attrTokens.nextToken().trim();
					attributes.add(name);
					
					Column column = new Column();
					column.setName(name);
					Datatype type = new Datatype();
					type.setName(TypeFacility.RUNTIME_NAMES.OBJECT);
					type.setJavaClassName(Object.class.getCanonicalName());
					column.setDatatype(type, true);
					columns.add(column);
				}
			} else {
				throw new TranslatorException(LDAPPlugin.Util.gs(LDAPPlugin.Event.TEIID12013, var));
			}
		}
		
		int searchScopeInt = buildSearchScope(searchScope);
		return new LDAPSearchDetails(contextName, searchScopeInt, criteria, null, countLimit, columns, timeLimit);
	}    
	
	private int buildSearchScope(String searchScope) {
		int searchScopeInt = 0;
		// this could be one of OBJECT_SCOPE, ONELEVEL_SCOPE, SUBTREE_SCOPE
		if (searchScope.equalsIgnoreCase("OBJECT_SCOPE")) { //$NON-NLS-1$
			searchScopeInt = SearchControls.OBJECT_SCOPE;
		}
		else if (searchScope.equalsIgnoreCase("ONELEVEL_SCOPE")) {//$NON-NLS-1$
			searchScopeInt = SearchControls.ONELEVEL_SCOPE;
		}
		else if (searchScope.equalsIgnoreCase("SUBTREE_SCOPE")) {//$NON-NLS-1$
			searchScopeInt =  SearchControls.SUBTREE_SCOPE;
		}
		return searchScopeInt;
	}	
}
