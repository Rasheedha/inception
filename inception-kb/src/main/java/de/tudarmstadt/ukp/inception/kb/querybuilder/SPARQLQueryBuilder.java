/*
 * Copyright 2019
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.kb.querybuilder;

import static com.github.jsonldjava.shaded.com.google.common.collect.Streams.concat;
import static de.tudarmstadt.ukp.inception.kb.IriConstants.FTS_LUCENE;
import static de.tudarmstadt.ukp.inception.kb.IriConstants.FTS_NONE;
import static de.tudarmstadt.ukp.inception.kb.IriConstants.FTS_VIRTUOSO;
import static de.tudarmstadt.ukp.inception.kb.IriConstants.hasImplicitNamespace;
import static de.tudarmstadt.ukp.inception.kb.querybuilder.Path.oneOrMore;
import static de.tudarmstadt.ukp.inception.kb.querybuilder.Path.zeroOrMore;
import static java.lang.Integer.toHexString;
import static java.lang.System.currentTimeMillis;
import static java.util.Collections.emptyList;
import static org.eclipse.rdf4j.sparqlbuilder.constraint.Expressions.and;
import static org.eclipse.rdf4j.sparqlbuilder.constraint.Expressions.function;
import static org.eclipse.rdf4j.sparqlbuilder.constraint.Expressions.notEquals;
import static org.eclipse.rdf4j.sparqlbuilder.constraint.Expressions.or;
import static org.eclipse.rdf4j.sparqlbuilder.constraint.SparqlFunction.CONTAINS;
import static org.eclipse.rdf4j.sparqlbuilder.constraint.SparqlFunction.LANG;
import static org.eclipse.rdf4j.sparqlbuilder.constraint.SparqlFunction.LANGMATCHES;
import static org.eclipse.rdf4j.sparqlbuilder.constraint.SparqlFunction.LCASE;
import static org.eclipse.rdf4j.sparqlbuilder.constraint.SparqlFunction.STR;
import static org.eclipse.rdf4j.sparqlbuilder.constraint.SparqlFunction.STRSTARTS;
import static org.eclipse.rdf4j.sparqlbuilder.graphpattern.GraphPatterns.filterExists;
import static org.eclipse.rdf4j.sparqlbuilder.graphpattern.GraphPatterns.optional;
import static org.eclipse.rdf4j.sparqlbuilder.graphpattern.GraphPatterns.union;
import static org.eclipse.rdf4j.sparqlbuilder.rdf.Rdf.bNode;
import static org.eclipse.rdf4j.sparqlbuilder.rdf.Rdf.iri;
import static org.eclipse.rdf4j.sparqlbuilder.rdf.Rdf.literalOf;
import static org.eclipse.rdf4j.sparqlbuilder.rdf.Rdf.literalOfLanguage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.sparqlbuilder.constraint.Expression;
import org.eclipse.rdf4j.sparqlbuilder.constraint.Expressions;
import org.eclipse.rdf4j.sparqlbuilder.constraint.Operand;
import org.eclipse.rdf4j.sparqlbuilder.constraint.SparqlFunction;
import org.eclipse.rdf4j.sparqlbuilder.core.Prefix;
import org.eclipse.rdf4j.sparqlbuilder.core.Projectable;
import org.eclipse.rdf4j.sparqlbuilder.core.SparqlBuilder;
import org.eclipse.rdf4j.sparqlbuilder.core.Variable;
import org.eclipse.rdf4j.sparqlbuilder.core.query.Queries;
import org.eclipse.rdf4j.sparqlbuilder.core.query.SelectQuery;
import org.eclipse.rdf4j.sparqlbuilder.graphpattern.GraphPattern;
import org.eclipse.rdf4j.sparqlbuilder.graphpattern.GraphPatterns;
import org.eclipse.rdf4j.sparqlbuilder.rdf.Iri;
import org.eclipse.rdf4j.sparqlbuilder.rdf.Rdf;
import org.eclipse.rdf4j.sparqlbuilder.rdf.RdfValue;
import org.eclipse.rdf4j.sparqlbuilder.util.SparqlBuilderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.inception.kb.IriConstants;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.graph.KBObject;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;

/**
 * Build queries against the KB.
 * 
 * <p>
 * <b>Handling of subclasses: </b>
 * Queries for subclasses return only resources which declare being a class via the class property
 * defined in the KB specification. This means that if the KB is configured to use rdfs:Class but a
 * subclass defines itself using owl:Class, then this subclass is *not* returned. We do presently
 * *not* support mixed schemes in a single KB.
 * </p>
 */
public class SPARQLQueryBuilder implements SPARQLQueryPrimaryConditions, SPARQLQueryOptionalElements
{
    private final static Logger LOG = LoggerFactory.getLogger(SPARQLQueryBuilder.class);

    public static final int DEFAULT_LIMIT = 0;
    
    public static final String VAR_SUBJECT_NAME = "s";
    public static final String VAR_LABEL_PROPERTY_NAME = "pLabel";
    public static final String VAR_LABEL_NAME = "l";
    public static final String VAR_LABEL_CANDIDATE_NAME = "lc";
    public static final String VAR_DESCRIPTION_NAME = "d";
    
    public static final Variable VAR_SUBJECT = SparqlBuilder.var(VAR_SUBJECT_NAME);
    public static final Variable VAR_LABEL = SparqlBuilder.var(VAR_LABEL_NAME);
    public static final Variable VAR_LABEL_CANDIDATE = SparqlBuilder.var(VAR_LABEL_CANDIDATE_NAME);
    public static final Variable VAR_LABEL_PROPERTY = SparqlBuilder.var(VAR_LABEL_PROPERTY_NAME);
    public static final Variable VAR_DESCRIPTION = SparqlBuilder.var(VAR_DESCRIPTION_NAME);
    public static final Variable VAR_DESC_CANDIDATE = SparqlBuilder.var("dc");

    public static final Prefix PREFIX_LUCENE_SEARCH = SparqlBuilder.prefix("search",
            iri("http://www.openrdf.org/contrib/lucenesail#"));
    public static final Iri LUCENE_QUERY = PREFIX_LUCENE_SEARCH.iri("query");
    public static final Iri LUCENE_PROPERTY = PREFIX_LUCENE_SEARCH.iri("property");
    public static final Iri LUCENE_SCORE = PREFIX_LUCENE_SEARCH.iri("score");
    public static final Iri LUCENE_SNIPPET = PREFIX_LUCENE_SEARCH.iri("snippet");
    
    public static final Iri OWL_INTERSECTIONOF = Rdf.iri(OWL.INTERSECTIONOF.stringValue());
    public static final Iri RDF_REST = Rdf.iri(RDF.REST.stringValue());
    public static final Iri RDF_FIRST = Rdf.iri(RDF.FIRST.stringValue());
        
    private final Set<Prefix> prefixes = new LinkedHashSet<>();
    private final Set<Projectable> projections = new LinkedHashSet<>();
    private final List<GraphPattern> primaryPatterns = new ArrayList<>();
    private final List<GraphPattern> primaryRestrictions = new ArrayList<>();
    private final List<GraphPattern> secondaryPatterns = new ArrayList<>();
    
    private boolean labelImplicitlyRetrieved = false;
    
    enum Priority {
        PRIMARY, PRIMARY_RESTRICTIONS, SECONDARY
    }
    
    /**
     * This flag is set internally to indicate whether the query should be skipped and an empty
     * result should always be returned. This can be the case, e.g. if the post-processing of
     * the query string against which to match a label causes the query to become empty.
     */
    private boolean returnEmptyResult = false;
    
    private final KnowledgeBase kb;
    private final Mode mode;
    
    /**
     * Case-insensitive mode is a best-effort approach. Depending on the underlying FTS, it may
     * or may not work.
     */
    private boolean caseInsensitive = true;
    
    private int limitOverride = DEFAULT_LIMIT;
    
    /**
     * This flag controls whether we attempt to drop duplicate labels and descriptions on the
     * side of the SPARQL server (true) or whether we try retrieving all labels and descriptions
     * which have either no language or match the KB language and then drop duplicates on our
     * side. Both approaches have benefits and draw-backs. In general, we try server-side
     * reduction to reduce the data being transferred across the wire. However, in some cases we
     * implicitly turn off server side reduction because (SSR) we have not been able to figure out
     * working SSR queries.
     * 
     * Benefits of SSR:
     * <ul>
     * <li>Less data to transfer across the wire</li>
     * <li>LIMIT works accurately (if we drop client side, we may end up with less than LIMIT 
     *     results)</li>
     * </ul>
     * 
     * Drawbacks of SSR:
     * <ul>
     * <li>More complex queries</li>
     * </ul>
     * 
     * @see #reduceRedundantResults(List)
     */
    // This is presently disabled because we cannot guarantee that the MIN operation
    // prefers the label with the language as opposed to "any label".
    private boolean serverSideReduce = false;
    
    private static enum Mode {
        ITEM, CLASS, INSTANCE, PROPERTY;
        
        protected Iri getLabelProperty(KnowledgeBase aKb) {
            switch (this) {
            case ITEM:
                return iri(aKb.getLabelIri().toString());
            case CLASS:
                return iri(aKb.getLabelIri().toString());
            case INSTANCE:
                return iri(aKb.getLabelIri().toString());
            case PROPERTY:
                return iri(aKb.getPropertyLabelIri().toString());
            default:
                throw new IllegalStateException("Unsupported mode: " + this);
            }
        }

        protected Iri getDescriptionProperty(KnowledgeBase aKb) {
            switch (this) {
            case ITEM:
                return iri(aKb.getDescriptionIri().toString());
            case CLASS:
                return iri(aKb.getDescriptionIri().toString());
            case INSTANCE:
                return iri(aKb.getDescriptionIri().toString());
            case PROPERTY:
                return iri(aKb.getPropertyDescriptionIri().toString());
            default:
                throw new IllegalStateException("Unsupported mode: " + this);
            }
        }
        
        /**
         * @see SPARQLQueryPrimaryConditions#descendantsOf(String)
         */
        protected GraphPattern descendentsPattern(KnowledgeBase aKB, Iri aContext)
        {
            Iri typeOfProperty = Rdf.iri(aKB.getTypeIri().toString());
            Iri subClassProperty = Rdf.iri(aKB.getSubclassIri().toString());
                        
            switch (this) {
            case ITEM: {
                List<GraphPattern> classPatterns = new ArrayList<>();
                classPatterns.add(
                        VAR_SUBJECT.has(() -> subClassProperty.getQueryString() + "+", aContext));
                classPatterns.add(VAR_SUBJECT
                        .has(Path.of(typeOfProperty, zeroOrMore(subClassProperty)), aContext));
                if (OWL.CLASS.equals(aKB.getClassIri())) {
                    classPatterns.add(VAR_SUBJECT.has(
                            Path.of(OWL_INTERSECTIONOF, zeroOrMore(RDF_REST), RDF_FIRST),
                            aContext));
                }
                
                return GraphPatterns.union(classPatterns.stream().toArray(GraphPattern[]::new));
            }
            case CLASS: {
                List<GraphPattern> classPatterns = new ArrayList<>();
                classPatterns.add(
                        VAR_SUBJECT.has(() -> subClassProperty.getQueryString() + "+", aContext));
                if (OWL.CLASS.equals(aKB.getClassIri())) {
                    classPatterns.add(VAR_SUBJECT.has(
                            Path.of(OWL_INTERSECTIONOF, zeroOrMore(RDF_REST), RDF_FIRST),
                            aContext));
                }

                return GraphPatterns.union(classPatterns.stream().toArray(GraphPattern[]::new));
            }
            case INSTANCE:
                return VAR_SUBJECT.has(Path.of(typeOfProperty, zeroOrMore(subClassProperty)),
                        aContext);
            default:
                throw new IllegalStateException("Unsupported mode: " + this);
            }            
        }

        /**
         * @see SPARQLQueryPrimaryConditions#ancestorsOf(String)
         */
        protected GraphPattern ancestorsPattern(KnowledgeBase aKB, Iri aContext)
        {
            Iri typeOfProperty = Rdf.iri(aKB.getTypeIri().toString());
            Iri subClassProperty = Rdf.iri(aKB.getSubclassIri().toString());
                        
            switch (this) {
            case ITEM:
            case CLASS:
            case INSTANCE: {
                List<GraphPattern> classPatterns = new ArrayList<>();
                classPatterns.add(
                        aContext.has(Path.of(oneOrMore(subClassProperty)), VAR_SUBJECT));
                classPatterns.add(aContext
                        .has(Path.of(typeOfProperty, zeroOrMore(subClassProperty)), VAR_SUBJECT));
                if (OWL.CLASS.equals(aKB.getClassIri())) {
                    classPatterns.add(aContext.has(
                            Path.of(OWL_INTERSECTIONOF, zeroOrMore(RDF_REST), RDF_FIRST),
                            VAR_SUBJECT));
                }
                
                return union(classPatterns.stream().toArray(GraphPattern[]::new));
            }
            default:
                throw new IllegalStateException("Unsupported mode: " + this);
            }            
        }
        
        /**
         * @see SPARQLQueryPrimaryConditions#childrenOf(String)
         */
        protected GraphPattern childrenPattern(KnowledgeBase aKB, Iri aContext)
        {
            Iri subClassProperty = Rdf.iri(aKB.getSubclassIri().toString());
                        
            switch (this) {
            case CLASS: {
                List<GraphPattern> classPatterns = new ArrayList<>();
                classPatterns.add(VAR_SUBJECT.has(subClassProperty, aContext));
                if (OWL.CLASS.equals(aKB.getClassIri())) {
                    classPatterns.add(VAR_SUBJECT.has(
                            Path.of(OWL_INTERSECTIONOF, zeroOrMore(RDF_REST), RDF_FIRST),
                            aContext));
                }
                
                return union(classPatterns.stream().toArray(GraphPattern[]::new));
            }
            default:
                throw new IllegalStateException("Can only request children of classes");
            }            
        }
        
        /**
         * @see SPARQLQueryPrimaryConditions#parentsOf(String)
         */
        protected GraphPattern parentsPattern(KnowledgeBase aKB, Iri aContext)
        {
            Iri subClassProperty = Rdf.iri(aKB.getSubclassIri().toString());
             
            switch (this) {
            case CLASS: {
                List<GraphPattern> classPatterns = new ArrayList<>();
                classPatterns.add(aContext.has(subClassProperty, VAR_SUBJECT));
                if (OWL.CLASS.equals(aKB.getClassIri())) {
                    classPatterns.add(aContext.has(
                            Path.of(OWL_INTERSECTIONOF, zeroOrMore(RDF_REST), RDF_FIRST),
                            VAR_SUBJECT));
                }
                
                return union(classPatterns.stream().toArray(GraphPattern[]::new));
            }
            default:
                throw new IllegalStateException("Can only request parents of classes");
            }            
        }

        /**
         * @see SPARQLQueryPrimaryConditions#roots()
         */
        protected GraphPattern rootsPattern(KnowledgeBase aKb)
        {
            Iri classIri = Rdf.iri(aKb.getClassIri().toString());
            Iri subClassProperty = Rdf.iri(aKb.getSubclassIri().toString());
            Iri typeOfProperty = Rdf.iri(aKb.getTypeIri().toString());
            Variable otherSubclass = SparqlBuilder.var("otherSubclass");
            
            switch (this) {
            case CLASS: {
                List<GraphPattern> rootPatterns = new ArrayList<>();

                List<IRI> rootConcepts = aKb.getRootConcepts();
                if (rootConcepts != null && !rootConcepts.isEmpty()) {
                    rootPatterns.add(new ValuesPattern(VAR_SUBJECT, rootConcepts.stream()
                            .map(iri -> Rdf.iri(iri.stringValue())).collect(Collectors.toList())));
                }
                else {
                    List<GraphPattern> classPatterns = new ArrayList<>();
                    classPatterns.add(VAR_SUBJECT.has(subClassProperty, otherSubclass)
                                            .filter(notEquals(VAR_SUBJECT, otherSubclass)));
                    if (OWL.CLASS.equals(aKb.getClassIri())) {
                        classPatterns.add(VAR_SUBJECT.has(OWL_INTERSECTIONOF, bNode()));
                    }
                    
                    rootPatterns.add(union(new GraphPattern[] {
                            // ... it is explicitly defined as being a class
                            VAR_SUBJECT.has(typeOfProperty, classIri),
                            // ... it has any subclass
                            Rdf.bNode().has(subClassProperty, VAR_SUBJECT) }).filterNotExists(
                                    union(classPatterns.stream().toArray(GraphPattern[]::new))));
                }
                
                return GraphPatterns
                        .and(rootPatterns.toArray(new GraphPattern[rootPatterns.size()]));
            }
            default:
                throw new IllegalStateException("Can only root classes");
            }            
        }
    }
    
    /**
     * Retrieve classes and instances.
     */
    public static SPARQLQueryPrimaryConditions forItems(KnowledgeBase aKB)
    {
        return new SPARQLQueryBuilder(aKB, Mode.ITEM);
    }

    /**
     * Retrieve classes.
     */
    public static SPARQLQueryPrimaryConditions forClasses(KnowledgeBase aKB)
    {
        SPARQLQueryBuilder builder = new SPARQLQueryBuilder(aKB, Mode.CLASS);
        builder.limitToClasses();
        return builder;
    }

    /**
     * Retrieve instances.
     */
    public static SPARQLQueryPrimaryConditions forInstances(KnowledgeBase aKB)
    {
        SPARQLQueryBuilder builder = new SPARQLQueryBuilder(aKB, Mode.INSTANCE);
        builder.limitToInstances();
        return builder;
    }

    /**
     * Retrieve properties.
     */
    public static SPARQLQueryPrimaryConditions forProperties(KnowledgeBase aKB)
    {
        return new SPARQLQueryBuilder(aKB, Mode.PROPERTY);
    }

    private SPARQLQueryBuilder(KnowledgeBase aKB, Mode aMode)
    {
        kb = aKB;
        mode = aMode;
    }
    
    private void addPattern(Priority aPriority, GraphPattern aPattern)
    {
        switch (aPriority) {
        case PRIMARY:
            primaryPatterns.add(aPattern);
            break;
        case PRIMARY_RESTRICTIONS:
            primaryRestrictions.add(aPattern);
            break;
        case SECONDARY:
            secondaryPatterns.add(aPattern);
            break;
        default:
            throw new IllegalArgumentException("Unknown priority: [" + aPriority + "]");
        }
    }
    
    private boolean hasPrimaryPatterns()
    {
        // If we force the query to return an empty result, that means that we intentionally skipped
        // adding a primary query
        return returnEmptyResult || !primaryPatterns.isEmpty();
    }
    
    private Projectable getLabelProjection()
    {
        if (serverSideReduce) {
            return Expressions.min(VAR_LABEL_CANDIDATE).as(VAR_LABEL);
        }
        else {
            return VAR_LABEL_CANDIDATE;
        }
    }

    private Projectable getDescriptionProjection()
    {
        if (serverSideReduce) {
            return Expressions.min(VAR_DESC_CANDIDATE).as(VAR_DESCRIPTION);
        }
        else {
            return VAR_DESC_CANDIDATE;
        }
    }

    @Override
    public SPARQLQueryOptionalElements limit(int aLimit)
    {
        limitOverride = aLimit;
        return this;
    }

    @Override
    public SPARQLQueryOptionalElements caseSensitive()
    {
        caseInsensitive = false;
        return this;
    }

    @Override
    public SPARQLQueryOptionalElements caseSensitive(boolean aEnabled)
    {
        caseInsensitive = !aEnabled;
        return this;
    }

    @Override
    public SPARQLQueryOptionalElements caseInsensitive()
    {
        caseInsensitive = true;
        return this;
    }

    /**
     * Generates a pattern which binds all sub-properties of the label property to the given 
     * variable. 
     */
    private GraphPattern bindLabelProperties(Variable aVariable)
    {
        Iri pLabel = mode.getLabelProperty(kb);
        Iri pSubProperty = Rdf.iri(kb.getSubPropertyIri().stringValue()); 
        
        return optional(aVariable.has(Path.of(zeroOrMore(pSubProperty)), pLabel));
    }

    @Override
    public SPARQLQueryBuilder withLabelMatchingExactlyAnyOf(String... aValues)
    {
        if (aValues.length == 0) {
            returnEmptyResult = true;
            return this;
        }
        
        IRI ftsMode = kb.getFullTextSearchIri();
        
        if (FTS_LUCENE.equals(ftsMode)) {
            addPattern(Priority.PRIMARY, withLabelMatchingExactlyAnyOf_RDF4J_FTS(aValues));
        }
        else if (FTS_VIRTUOSO.equals(ftsMode)) {
            addPattern(Priority.PRIMARY, withLabelMatchingExactlyAnyOf_Virtuoso_FTS(aValues));
        }
        else if (FTS_NONE.equals(ftsMode) || ftsMode == null) {
            addPattern(Priority.PRIMARY, withLabelMatchingExactlyAnyOf_No_FTS(aValues));
        }
        else {
            throw new IllegalStateException(
                    "Unknown FTS mode: [" + kb.getFullTextSearchIri() + "]");
        }
        
        // Retain only the first description - do this here since we change the server-side reduce
        // flag above when using Lucene FTS
        projections.add(getLabelProjection());
        labelImplicitlyRetrieved = true;
        
        return this;
    }
    
    private GraphPattern withLabelMatchingExactlyAnyOf_No_FTS(String[] aValues)
    {
        List<RdfValue> values = new ArrayList<>();
        String language =  kb.getDefaultLanguage();
        
        for (String value : aValues) {
            if (StringUtils.isBlank(value)) {
                continue;
            }
            
            if (language != null) {
                values.add(literalOfLanguage(value, language));
            }
            
            values.add(literalOf(value));
        }
        
        return GraphPatterns.and(
            bindLabelProperties(VAR_LABEL_PROPERTY),
            new ValuesPattern(VAR_LABEL_CANDIDATE, values),
            VAR_SUBJECT.has(VAR_LABEL_PROPERTY, VAR_LABEL_CANDIDATE));
    }
    
    private GraphPattern withLabelMatchingExactlyAnyOf_RDF4J_FTS(String[] aValues)
    {
        prefixes.add(PREFIX_LUCENE_SEARCH);
        
        Iri pLabelFts = iri(IriConstants.FTS_LUCENE.toString());
        
        List<GraphPattern> valuePatterns = new ArrayList<>();
        for (String value : aValues) {
            if (StringUtils.isBlank(value)) {
                continue;
            }
            
            valuePatterns.add(VAR_SUBJECT
                    .has(pLabelFts,
                            bNode(LUCENE_QUERY, literalOf(value))
                            .andHas(LUCENE_PROPERTY, VAR_LABEL_PROPERTY))
                    .andHas(VAR_LABEL_PROPERTY, VAR_LABEL_CANDIDATE)
                    .filter(equalsPattern(VAR_LABEL_CANDIDATE, value, kb)));
        }
        
        return GraphPatterns.and(
                bindLabelProperties(VAR_LABEL_PROPERTY),
                union(valuePatterns.toArray(new GraphPattern[valuePatterns.size()])));
    }
    
    private GraphPattern withLabelMatchingExactlyAnyOf_Virtuoso_FTS(String[] aValues)
    {
        Iri pLabelFts = iri(FTS_VIRTUOSO.toString());
        
        List<GraphPattern> valuePatterns = new ArrayList<>();
        for (String value : aValues) {
            String sanitizedValue = sanitizeQueryStringForFTS(value);
            
            if (StringUtils.isBlank(sanitizedValue)) {
                continue;
            }
                        
            valuePatterns.add(VAR_SUBJECT
                    .has(VAR_LABEL_PROPERTY, VAR_LABEL_CANDIDATE)
                    .and(VAR_LABEL_CANDIDATE.has(pLabelFts,literalOf("\"" + sanitizedValue + "\"")))
                    .filter(equalsPattern(VAR_LABEL_CANDIDATE, value, kb)));
        }
        
        return GraphPatterns.and(
                bindLabelProperties(VAR_LABEL_PROPERTY),
                union(valuePatterns.toArray(new GraphPattern[valuePatterns.size()])));
    }

    @Override
    public SPARQLQueryBuilder withLabelStartingWith(String aPrefixQuery)
    {
        if (aPrefixQuery.length() == 0) {
            returnEmptyResult = true;
            return this;
        }
        
        
        IRI ftsMode = kb.getFullTextSearchIri();
        
        if (IriConstants.FTS_LUCENE.equals(ftsMode)) {
            addPattern(Priority.PRIMARY, withLabelStartingWith_RDF4J_FTS(aPrefixQuery));
        }
        else if (IriConstants.FTS_VIRTUOSO.equals(ftsMode)) {
            addPattern(Priority.PRIMARY, withLabelStartingWith_Virtuoso_FTS(aPrefixQuery));
        }
        else if (IriConstants.FTS_NONE.equals(ftsMode) || ftsMode == null) {
            addPattern(Priority.PRIMARY, withLabelStartingWith_No_FTS(aPrefixQuery));
        }
        else {
            throw new IllegalStateException(
                    "Unknown FTS mode: [" + kb.getFullTextSearchIri() + "]");
        }
        
        // Retain only the first description - do this here since we change the server-side reduce
        // flag above when using Lucene FTS
        projections.add(getLabelProjection());
        labelImplicitlyRetrieved = true;
        
        return this;
    }
    
    @Override
    public SPARQLQueryBuilder withLabelContainingAnyOf(String... aValues)
    {
        if (aValues.length == 0) {
            returnEmptyResult = true;
            return this;
        }
        
        IRI ftsMode = kb.getFullTextSearchIri();
        
        if (IriConstants.FTS_LUCENE.equals(ftsMode)) {
            addPattern(Priority.PRIMARY, withLabelContainingAnyOf_RDF4J_FTS(aValues));
        }
        else if (IriConstants.FTS_VIRTUOSO.equals(ftsMode)) {
            addPattern(Priority.PRIMARY, withLabelContainingAnyOf_Virtuoso_FTS(aValues));
        }
        else if (IriConstants.FTS_NONE.equals(ftsMode) || ftsMode == null) {
            addPattern(Priority.PRIMARY, withLabelContainingAnyOf_No_FTS(aValues));
        }
        else {
            throw new IllegalStateException(
                    "Unknown FTS mode: [" + kb.getFullTextSearchIri() + "]");
        }
        
        // Retain only the first description - do this here since we change the server-side reduce
        // flag above when using Lucene FTS
        projections.add(getLabelProjection());
        labelImplicitlyRetrieved = true;
        
        return this;
    }

    private GraphPattern withLabelContainingAnyOf_No_FTS(String... aValues)
    {
        List<GraphPattern> valuePatterns = new ArrayList<>();
        for (String value : aValues) {
            if (StringUtils.isBlank(value)) {
                continue;
            }
            
            valuePatterns.add(VAR_SUBJECT
                    .has(VAR_LABEL_PROPERTY, VAR_LABEL_CANDIDATE)
                    .filter(containsPattern(VAR_LABEL_CANDIDATE, value)));
        }
        
        return GraphPatterns.and(
                bindLabelProperties(VAR_LABEL_PROPERTY),
                union(valuePatterns.toArray(new GraphPattern[valuePatterns.size()])));
    }
    
    private GraphPattern withLabelContainingAnyOf_RDF4J_FTS(String[] aValues)
    {
        prefixes.add(PREFIX_LUCENE_SEARCH);
        
        Iri pLabelFts = iri(IriConstants.FTS_LUCENE.toString());
        
        List<GraphPattern> valuePatterns = new ArrayList<>();
        for (String value : aValues) {
            String sanitizedValue = sanitizeQueryStringForFTS(value);

            if (StringUtils.isBlank(sanitizedValue)) {
                continue;
            }

            valuePatterns.add(VAR_SUBJECT
                    .has(pLabelFts,
                            bNode(LUCENE_QUERY, literalOf(sanitizedValue + "*"))
                            .andHas(LUCENE_PROPERTY, VAR_LABEL_PROPERTY))
                    .andHas(VAR_LABEL_PROPERTY, VAR_LABEL_CANDIDATE)
                    .filter(containsPattern(VAR_LABEL_CANDIDATE, value)));
        }
        
        return GraphPatterns.and(
                bindLabelProperties(VAR_LABEL_PROPERTY),
                union(valuePatterns.toArray(new GraphPattern[valuePatterns.size()])));
    }

    private GraphPattern withLabelContainingAnyOf_Virtuoso_FTS(String[] aValues)
    {
        Iri pLabelFts = iri(FTS_VIRTUOSO.toString());
        
        List<GraphPattern> valuePatterns = new ArrayList<>();
        for (String value : aValues) {
            String sanitizedValue = sanitizeQueryStringForFTS(value);
            
            if (StringUtils.isBlank(sanitizedValue)) {
                continue;
            }
                        
            valuePatterns.add(VAR_SUBJECT
                    .has(VAR_LABEL_PROPERTY, VAR_LABEL_CANDIDATE)
                    .and(VAR_LABEL_CANDIDATE.has(pLabelFts,literalOf("\"" + sanitizedValue + "\"")))
                    .filter(containsPattern(VAR_LABEL_CANDIDATE, value)));
        }
        
        return GraphPatterns.and(
                bindLabelProperties(VAR_LABEL_PROPERTY),
                union(valuePatterns.toArray(new GraphPattern[valuePatterns.size()])));
    }

    private GraphPattern withLabelStartingWith_No_FTS(String aPrefixQuery)
    {
        if (aPrefixQuery.isEmpty()) {
            returnEmptyResult = true;
        }
        
        return GraphPatterns.and(
                bindLabelProperties(VAR_LABEL_PROPERTY),
                VAR_SUBJECT.has(VAR_LABEL_PROPERTY, VAR_LABEL_CANDIDATE)
                        .filter(startsWithPattern(VAR_LABEL_CANDIDATE, aPrefixQuery)));
    }

    private GraphPattern withLabelStartingWith_Virtuoso_FTS(String aPrefixQuery)
    {
        StringBuilder ftsQueryString = new StringBuilder();
        ftsQueryString.append("\"");
        
        // Strip single quotes and asterisks because they have special semantics
        String sanitizedQuery = sanitizeQueryStringForFTS(aPrefixQuery);
        
        // If the query string entered by the user does not end with a space character, then
        // we assume that the user may not yet have finished writing the word and add a
        // wildcard
        if (!aPrefixQuery.endsWith(" ")) {
            String[] queryTokens = sanitizedQuery.split(" ");
            for (int i = 0; i < queryTokens.length; i++) {
                if (i > 0) {
                    ftsQueryString.append(" ");
                }
                
                // Virtuoso requires that a token has at least 4 characters before it can be 
                // used with a wildcard. If the last token has less than 4 characters, we simply
                // drop it to avoid the user hitting a point where the auto-suggesions suddenly
                // are empty. If the token 4 or more, we add the wildcard.
                if (i == (queryTokens.length - 1)) {
                    if (queryTokens[i].length() >= 4) {
                        ftsQueryString.append(queryTokens[i]);
                        ftsQueryString.append("*");
                    }
                }
                else {
                    ftsQueryString.append(queryTokens[i]);
                }
            }
        }
        else {
            ftsQueryString.append(sanitizedQuery);
        }
        
        ftsQueryString.append("\"");
        
        // If the query string was reduced to nothing, then the query should always return an empty
        // result.
        if (ftsQueryString.length() == 2) {
            returnEmptyResult = true;
        }
        
        Iri pLabelFts = iri(FTS_VIRTUOSO.toString());
        
        // Locate all entries where the label contains the prefix (using the FTS) and then
        // filter them by those which actually start with the prefix.
        return GraphPatterns.and(bindLabelProperties(VAR_LABEL_PROPERTY),
                VAR_SUBJECT.has(VAR_LABEL_PROPERTY, VAR_LABEL_CANDIDATE)
                        .and(VAR_LABEL_CANDIDATE.has(pLabelFts,
                                literalOf(ftsQueryString.toString())))
                        .filter(startsWithPattern(VAR_LABEL_CANDIDATE, aPrefixQuery)));
    }

    private GraphPattern withLabelStartingWith_RDF4J_FTS(String aPrefixQuery)
    {
        // REC: Haven't been able to get this to work with server-side reduction, so implicitly
        // turning it off here.
        serverSideReduce = false;
        
        prefixes.add(PREFIX_LUCENE_SEARCH);
        
        String queryString = aPrefixQuery.trim();
        
        if (queryString.isEmpty()) {
            returnEmptyResult = true;
        }

        // If the query string entered by the user does not end with a space character, then
        // we assume that the user may not yet have finished writing the word and add a
        // wildcard
        if (!aPrefixQuery.endsWith(" ")) {
            queryString += "*";
        }

        Iri pLabelFts = iri(IriConstants.FTS_LUCENE.toString());

        // Locate all entries where the label contains the prefix (using the FTS) and then
        // filter them by those which actually start with the prefix.
        return GraphPatterns.and(
                bindLabelProperties(VAR_LABEL_PROPERTY),
                VAR_SUBJECT.has(pLabelFts,bNode(LUCENE_QUERY, literalOf(queryString))
                        .andHas(LUCENE_PROPERTY, VAR_LABEL_PROPERTY))
                        .andHas(VAR_LABEL_PROPERTY, VAR_LABEL_CANDIDATE)
                        .filter(startsWithPattern(VAR_LABEL_CANDIDATE, aPrefixQuery)));
    }

    private Expression<?> startsWithPattern(Variable aVariable, String aPrefixQuery)
    {
        return matchString(STRSTARTS, aVariable, aPrefixQuery);
    }

    private Expression<?> containsPattern(Variable aVariable, String aSubstring)
    {
        return matchString(CONTAINS, aVariable, aSubstring);
    }

    private Expression<?> equalsPattern(Variable aVariable, String aValue,
            KnowledgeBase aKB)
    {
        String language = aKB.getDefaultLanguage();
        
        List<Expression<?>> expressions = new ArrayList<>();
        
        // If case-insensitive mode is enabled, then lower-case the strings
        Operand variable = aVariable;
        String value = aValue;
        if (caseInsensitive) {
            variable = function(LCASE, function(STR, variable));
            value = value.toLowerCase();
        }
        
        // Match with default language
        if (language != null) {
            expressions.add(Expressions.equals(variable, literalOfLanguage(value, language)));
        }
        
        // Match without language
        expressions.add(Expressions.equals(variable, literalOf(value)));
        
        return or(expressions.toArray(new Expression<?>[expressions.size()]));
    }

    private Expression<?> matchString(SparqlFunction aFunction, Variable aVariable,
            String aValue)
    {
        String language = kb.getDefaultLanguage();

        List<Expression<?>> expressions = new ArrayList<>();

        // If case-insensitive mode is enabled, then lower-case the strings
        Operand variable = aVariable;
        String value = aValue;
        if (caseInsensitive) {
            variable = function(LCASE, function(STR, variable));
            value = value.toLowerCase();
        }
        
        // Match with default language
        if (language != null) {
            expressions.add(and(function(aFunction, variable, literalOf(value)),
                    function(LANGMATCHES, function(LANG, aVariable), literalOf(language)))
                            .parenthesize());
        }

        // Match without language
        expressions.add(function(aFunction, variable, literalOf(value)));
        
        return or(expressions.toArray(new Expression<?>[expressions.size()]));
    }

    @Override
    public SPARQLQueryPrimaryConditions roots()
    {
        addPattern(Priority.PRIMARY, mode.rootsPattern(kb));
        
        return this;
    }

    @Override
    public SPARQLQueryPrimaryConditions ancestorsOf(String aItemIri)
    {
        Iri contextIri = Rdf.iri(aItemIri);
        
        addPattern(Priority.PRIMARY, mode.ancestorsPattern(kb, contextIri));
        
        return this;
    }
    
    @Override
    public SPARQLQueryPrimaryConditions descendantsOf(String aClassIri)
    {
        Iri contextIri = Rdf.iri(aClassIri);
        
        addPattern(Priority.PRIMARY, mode.descendentsPattern(kb, contextIri));
        
        return this;
    }

    @Override
    public SPARQLQueryPrimaryConditions childrenOf(String aClassIri)
    {
        Iri contextIri = Rdf.iri(aClassIri);
        
        addPattern(Priority.PRIMARY, mode.childrenPattern(kb, contextIri));
        
        return this;
    }

    @Override
    public SPARQLQueryPrimaryConditions parentsOf(String aClassIri)
    {
        Iri contextIri = Rdf.iri(aClassIri);
        
        addPattern(Priority.PRIMARY, mode.parentsPattern(kb, contextIri));
        
        return this;
    }

    private void limitToClasses()
    {
        Iri classIri = Rdf.iri(kb.getClassIri().toString());
        Iri subClassProperty = Rdf.iri(kb.getSubclassIri().toString());
        Iri typeOfProperty = Rdf.iri(kb.getTypeIri().toString());
        
        // An item is a class if ...
        addPattern(Priority.PRIMARY_RESTRICTIONS, filterExists(union(new GraphPattern[] {
                // ... it is explicitly defined as being a class
                VAR_SUBJECT.has(typeOfProperty, classIri),
                // ... it has any subclass
                bNode().has(subClassProperty, VAR_SUBJECT),
                // ... it has any superclass
                VAR_SUBJECT.has(subClassProperty, bNode())})));
    }
    
    private void limitToInstances()
    {
        Iri classIri = Rdf.iri(kb.getClassIri().toString());
        Iri subClassProperty = Rdf.iri(kb.getSubclassIri().toString());
        Iri typeOfProperty = Rdf.iri(kb.getTypeIri().toString());
        
        // An item is a class if ...
        addPattern(Priority.PRIMARY_RESTRICTIONS, VAR_SUBJECT.has(typeOfProperty, bNode())
                // ... it is explicitly defined as being a class
                .filterNotExists(VAR_SUBJECT.has(typeOfProperty, classIri))
                // ... it has any subclass
                .filterNotExists(bNode().has(subClassProperty, VAR_SUBJECT))
                // ... it has any superclass
                .filterNotExists(VAR_SUBJECT.has(subClassProperty, bNode())));
    }
        
    @Override
    public SPARQLQueryOptionalElements retrieveLabel()
    {
        // If the label is already retrieved, do nothing
        if (labelImplicitlyRetrieved) {
            return this;
        }
        
        if (!hasPrimaryPatterns()) {
            throw new IllegalStateException("Call a method which adds primary patterns first");
        }
        
        // Retain only the first description
        projections.add(getLabelProjection());
        
        String language = kb.getDefaultLanguage();
        
        List<GraphPattern> labelPatterns = new ArrayList<>();

        // Find all labels without any language
        labelPatterns.add(VAR_SUBJECT.has(VAR_LABEL_PROPERTY, VAR_LABEL_CANDIDATE));

        // Find all labels corresponding to the KB language
        if (language != null) {
            labelPatterns.add(VAR_SUBJECT.has(VAR_LABEL_PROPERTY, VAR_LABEL_CANDIDATE)
                    .filter(function(LANGMATCHES, function(LANG, VAR_LABEL_CANDIDATE), 
                            literalOf(language))));
        }

        addPattern(Priority.SECONDARY, bindLabelProperties(VAR_LABEL_PROPERTY));
        
        // Virtuoso has trouble with multiple OPTIONAL clauses causing results which would 
        // normally match to be removed from the results set. Using a UNION seems to address this
        //labelPatterns.forEach(pattern -> addPattern(Priority.SECONDARY, optional(pattern)));
        addPattern(Priority.SECONDARY,
                optional(union(labelPatterns.toArray(new GraphPattern[labelPatterns.size()]))));
        
        return this;
    }

    @Override
    public SPARQLQueryOptionalElements retrieveDescription()
    {
        if (!hasPrimaryPatterns()) {
            throw new IllegalStateException("Call a method which adds primary patterns first");
        }
        
        // Retain only the first description
        projections.add(getDescriptionProjection());
        
        String language = kb.getDefaultLanguage();
        Iri descProperty = mode.getDescriptionProperty(kb);

        List<GraphPattern> descriptionPatterns = new ArrayList<>();

        // Find all descriptions corresponding to the KB language
        if (language != null) {
            descriptionPatterns.add(VAR_SUBJECT.has(descProperty, VAR_DESC_CANDIDATE)
                    .filter(function(LANGMATCHES, function(LANG, VAR_DESC_CANDIDATE), 
                            literalOf(language))));
        }

        // Find all descriptions without any language
        descriptionPatterns.add(VAR_SUBJECT.has(descProperty, VAR_DESC_CANDIDATE));

        // Virtuoso has trouble with multiple OPTIONAL clauses causing results which would 
        // normally match to be removed from the results set. Using a UNION seems to address this
        //descriptionPatterns.forEach(pattern -> addPattern(Priority.SECONDARY, optional(pattern)));
        addPattern(Priority.SECONDARY, optional(
                union(descriptionPatterns.toArray(new GraphPattern[descriptionPatterns.size()]))));
        
        return this;
    }
    
    @Override
    public SelectQuery selectQuery()
    {
        // Must add it anyway because we group by it
        projections.add(VAR_SUBJECT);

        SelectQuery query = Queries.SELECT().distinct();
        prefixes.forEach(query::prefix);
        projections.forEach(query::select);
        
        // First add the primary patterns and high-level restrictions (e.g. limits to classes or
        // instances) - this is important because Virtuoso has trouble when combining UNIONS,
        // property paths FILTERS and OPTIONALS (which we do a lot). It seems to help when we put
        // the FILTERS together with the primary part of the query into a group.
        // See: https://github.com/openlink/virtuoso-opensource/issues/831
        query.where(() -> SparqlBuilderUtils.getBracedString(
                GraphPatterns.and(concat(primaryPatterns.stream(), primaryRestrictions.stream())
                        .toArray(GraphPattern[]::new)).getQueryString()));
        
        // Then add the optional elements
        secondaryPatterns.stream().forEach(query::where);
        
        if (serverSideReduce) {
            query.groupBy(VAR_SUBJECT);
        }
        
        if (kb.getDefaultDatasetIri() != null) {
            query.from(SparqlBuilder.dataset(
                    SparqlBuilder.from(Rdf.iri(kb.getDefaultDatasetIri().stringValue()))));
        }
        
        query.limit(limitOverride > 0 ? limitOverride : kb.getMaxResults());
        
        return query;
    }
    
    @Override
    public List<KBHandle> asHandles(RepositoryConnection aConnection, boolean aAll)
    {
        long startTime = currentTimeMillis();
        String queryId = toHexString(hashCode());

        String queryString = selectQuery().getQueryString();
        //queryString = QueryParserUtil.parseQuery(QueryLanguage.SPARQL, queryString, null)
        //        .toString();
        LOG.trace("[{}] Query: {}", queryId, queryString);

        List<KBHandle> results;
        if (returnEmptyResult) {
            results = emptyList();
            
            LOG.debug("[{}] Query was skipped because it would not return any results anyway",
                    queryId);
        }
        else {
            TupleQuery tupleQuery = aConnection.prepareTupleQuery(queryString);
            results = evaluateListQuery(tupleQuery, aAll);
            results.sort(Comparator.comparing(KBObject::getUiLabel, String.CASE_INSENSITIVE_ORDER));
            
            LOG.debug("[{}] Query returned {} results in {}ms", queryId, results.size(),
                    currentTimeMillis() - startTime);
        }

        return results;
    }
    
    /**
     * Execute the query and return {@code true} if the result set is not empty. This internally
     * limits the number of results requested via SPARQL to 1 and should complete faster than
     * retrieving the entire results set and checking whether it is empty.
     * 
     * @param aConnection
     *            a connection to a triple store.
     * @param aAll
     *            True if entities with implicit namespaces (e.g. defined by RDF)
     * @return {@code true} if the result set is not empty.
     */
    @Override
    public boolean exists(RepositoryConnection aConnection, boolean aAll)
    {
        long startTime = currentTimeMillis();
        String queryId = toHexString(hashCode());

        limit(1);
        
        SelectQuery query = selectQuery();
        
        String queryString = query.getQueryString();
        LOG.trace("[{}] Query: {}", queryId, queryString);

        if (returnEmptyResult) {
            LOG.debug("[{}] Query was skipped because it would not return any results anyway",
                    queryId);
            
            return false;
        }
        else {
            TupleQuery tupleQuery = aConnection.prepareTupleQuery(queryString);
            boolean result = !evaluateListQuery(tupleQuery, aAll).isEmpty();
            
            LOG.debug("[{}] Query returned {} in {}ms", queryId, result,
                    currentTimeMillis() - startTime);
            
            return result;
        }
    }
    
    /**
     * Method process the Tuple Query Results
     * 
     * @param tupleQuery
     *            Tuple Query Variable
     * @param aAll
     *            True if entities with implicit namespaces (e.g. defined by RDF)
     * @return list of all the {@link KBHandle}
     */
    private List<KBHandle> evaluateListQuery(TupleQuery tupleQuery, boolean aAll)
        throws QueryEvaluationException
    {
        TupleQueryResult result = tupleQuery.evaluate();        
        
        List<KBHandle> handles = new ArrayList<>();
        while (result.hasNext()) {
            BindingSet bindings = result.next();
            if (bindings.size() == 0) {
                continue;
            }
            
            LOG.trace("[{}] Bindings: {}", toHexString(hashCode()), bindings);

            String id = bindings.getBinding(VAR_SUBJECT_NAME).getValue().stringValue();
            if (!id.contains(":") || (!aAll && hasImplicitNamespace(kb, id))) {
                continue;
            }
            
            KBHandle handle = new KBHandle(id);
            handle.setKB(kb);
            
            extractLabel(handle, bindings);
            extractDescription(handle, bindings);
            extractRange(handle, bindings);
            extractDomain(handle, bindings);

            handles.add(handle);
        }
        
        if (serverSideReduce) {
            return handles;
        }
        else {
            return reduceRedundantResults(handles);
        }
    }
    
    /**
     * Make sure that each result is only represented once, preferably in the default language.
     */
    private List<KBHandle> reduceRedundantResults(List<KBHandle> aHandles)
    {
        Map<String, KBHandle> cMap = new LinkedHashMap<>();
        for (KBHandle handle : aHandles) {
            if (!cMap.containsKey(handle.getIdentifier())) {
                cMap.put(handle.getIdentifier(), handle);
            }
            else if (kb.getDefaultLanguage().equals(handle.getLanguage())) {
                cMap.put(handle.getIdentifier(), handle);
            }
        }
        
//        LOG.trace("Input: {}", aHandles);
//        LOG.trace("Output: {}", cMap.values());
        
        return new ArrayList<>(cMap.values());
    }
    
    private void extractLabel(KBHandle aTargetHandle, BindingSet aSourceBindings)
    {
        // If server-side reduce is used, the label is in VAR_LABEL_NAME
        Binding label = aSourceBindings.getBinding(VAR_LABEL_NAME);
        // If client-side reduce is used, the label is in VAR_LABEL_CANDIDATE_NAME
        Binding labelCandidate = aSourceBindings.getBinding(VAR_LABEL_CANDIDATE_NAME);
        Binding subPropertyLabel = aSourceBindings.getBinding("spl");
        if (label != null) {
            aTargetHandle.setName(label.getValue().stringValue());
            if (label.getValue() instanceof Literal) {
                Literal literal = (Literal) label.getValue();
                literal.getLanguage().ifPresent(aTargetHandle::setLanguage);
            }
        }
        else if (labelCandidate != null) {
            aTargetHandle.setName(labelCandidate.getValue().stringValue());
            if (labelCandidate.getValue() instanceof Literal) {
                Literal literal = (Literal) labelCandidate.getValue();
                literal.getLanguage().ifPresent(aTargetHandle::setLanguage);
            }
        }
        else if (subPropertyLabel != null) {
            aTargetHandle.setName(subPropertyLabel.getValue().stringValue());
            if (subPropertyLabel.getValue() instanceof Literal) {
                Literal literal = (Literal) subPropertyLabel.getValue();
                literal.getLanguage().ifPresent(aTargetHandle::setLanguage);
            }
        }
    }
    
    private void extractDescription(KBHandle aTargetHandle, BindingSet aSourceBindings)
    {
        Binding description = aSourceBindings.getBinding(VAR_DESCRIPTION_NAME);
        Binding descGeneral = aSourceBindings.getBinding("descGeneral");
        if (description != null) {
            aTargetHandle.setDescription(description.getValue().stringValue());
        }
        else if (descGeneral != null) {
            aTargetHandle.setDescription(descGeneral.getValue().stringValue());
        }
    }
    
    private void extractDomain(KBHandle aTargetHandle, BindingSet aSourceBindings)
    {
        Binding domain = aSourceBindings.getBinding("dom");
        if (domain != null) {
            aTargetHandle.setDomain(domain.getValue().stringValue());
        }
    }

    private void extractRange(KBHandle aTargetHandle, BindingSet aSourceBindings)
    {
        Binding range = aSourceBindings.getBinding("range");
        if (range != null) {
            aTargetHandle.setRange(range.getValue().stringValue());
        }
    }
    
    private String sanitizeQueryStringForFTS(String aQuery)
    {
        return aQuery.trim().replaceAll("[*\\p{Punct}]", " ").trim();
    }
}