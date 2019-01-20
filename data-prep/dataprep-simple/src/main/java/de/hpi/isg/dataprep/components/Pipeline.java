package de.hpi.isg.dataprep.components;

import de.hpi.isg.dataprep.context.DataContext;
import de.hpi.isg.dataprep.exceptions.PipelineSyntaxErrorException;
import de.hpi.isg.dataprep.metadata.*;
import de.hpi.isg.dataprep.model.dialects.FileLoadDialect;
import de.hpi.isg.dataprep.model.repository.ErrorRepository;
import de.hpi.isg.dataprep.model.repository.MetadataRepository;
import de.hpi.isg.dataprep.model.repository.ProvenanceRepository;
import de.hpi.isg.dataprep.model.target.data.ColumnCombination;
import de.hpi.isg.dataprep.model.target.errorlog.PipelineErrorLog;
import de.hpi.isg.dataprep.model.target.objects.TableMetadata;
import de.hpi.isg.dataprep.model.target.objects.Metadata;
import de.hpi.isg.dataprep.model.target.system.AbstractPipeline;
import de.hpi.isg.dataprep.model.target.system.AbstractPreparation;
import de.hpi.isg.dataprep.model.target.system.AbstractPreparator;
import de.hpi.isg.dataprep.write.FlatFileWriter;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.StructType;

import java.util.*;

/**
 * @author Lan Jiang
 * @since 2018/9/10
 */
public class Pipeline implements AbstractPipeline {

    private String name = "default-pipeline";

    /**
     * Specifies the maximum cardinality of a column combination that is passed as the parameter to preparators. Could be moved to the controller.
     * Allows use all columns if the value is Integer.MIN_VALUE.
     */
    private final static int MAX_CARDINALITY = Integer.MIN_VALUE;

    private MetadataRepository metadataRepository;
    private ProvenanceRepository provenanceRepository;
    private ErrorRepository errorRepository;

    private List<AbstractPreparation> preparations;

    private DecisionEngine decisionEngine = DecisionEngine.getInstance();

    //

    private int index = 0;

    /**
     * The raw data contains a set of {@link Row} instances. Each instance represent a line in a tabular data without schema definition,
     * i.e., each instance has only one attribute that represent the whole line, including content and utility characters.
     */
    private Dataset<Row> rawData;
    private Collection<ColumnCombination> columnCombinations;

    private DataContext dataContext;
    private String datasetName;

    private Pipeline() {
        this.metadataRepository = new MetadataRepository();
        this.provenanceRepository = new ProvenanceRepository();
        this.errorRepository = new ErrorRepository();
        this.preparations = new LinkedList<>();
    }

    public Pipeline(Dataset<Row> rawData) {
        this();
        this.rawData = rawData;
    }

    public Pipeline(String name, Dataset<Row> rawData) {
        this(rawData);
        this.name = name;
    }

    public Pipeline(DataContext dataContext) {
        this();
        this.dataContext = dataContext;
        this.rawData = dataContext.getDataFrame();
        this.datasetName = dataContext.getDialect().getTableName();
    }

    @Override
    public void addPreparation(AbstractPreparation preparation) {
        preparation.setPipeline(this);
        preparation.setPosition(index++);

//        preparation.getAbstractPreparator().buildMetadataSetup();
        this.preparations.add(preparation);
    }

    @Override
    public void checkPipelineErrors() throws PipelineSyntaxErrorException {
        MetadataRepository metadataRepository = this.metadataRepository;

        // the first preparator should not produce pipeline syntax error. Therefore, do not check the prerequisite for it.
        // Only add the toChange list to metadata repository.
        preparations.stream().forEachOrdered(preparation -> preparation.checkPipelineErrorWithPrevious(metadataRepository));

        long numberOfPipelineError = errorRepository.getErrorLogs().stream()
                .filter(errorLog -> errorLog instanceof PipelineErrorLog)
                .map(errorLog -> (PipelineErrorLog) errorLog)
                .count();
        if (numberOfPipelineError > 0) {
            throw new PipelineSyntaxErrorException("The pipeline contains syntax errors.");
        }

        // remove all the metadata assumed during the pipeline error check phase.
        metadataRepository.getMetadataPool().clear();
    }

    @Override
    public void executePipeline() throws Exception {
        // first time initialize metadata repository to check pipeline syntax errors.
        initMetadataRepository();

        buildMetadataSetup();

        try {
            checkPipelineErrors();
        } catch (PipelineSyntaxErrorException e) {
            // write the errorlog to disc.
            FlatFileWriter<ErrorRepository> flatFileWriter = new FlatFileWriter<>();
            flatFileWriter.write(this.getErrorRepository());
            throw e;
        }

        // here optimize the pipeline.

        // second time initialize metadata repository for preparation to execute the pipeline.
        initMetadataRepository();

//        this.buildColumnCombination();
//        for (AbstractPreparation preparation : preparations) {
//            this.getColumnCombinations()
//                    .forEach(columnCombination -> preparation.getAbstractPreparator().getApplicability().putIfAbsent(columnCombination, 0.0f));
//        }

        // execute the pipeline
        for (AbstractPreparation preparation : preparations) {
            preparation.getAbstractPreparator().execute();
        }
    }

    @Override
    public void initMetadataRepository() {
        FileLoadDialect dialect = this.dataContext.getDialect();

        Delimiter delimiter = new Delimiter(dialect.getDelimiter(), new TableMetadata(dialect.getTableName()));
        QuoteCharacter quoteCharacter = new QuoteCharacter(dialect.getQuoteChar(), new TableMetadata(dialect.getTableName()));
        EscapeCharacter escapeCharacter = new EscapeCharacter(dialect.getEscapeChar(), new TableMetadata(dialect.getTableName()));
        HeaderExistence headerExistence = new HeaderExistence(dialect.getHasHeader().equals("true"), new TableMetadata(dialect.getTableName()));

        List<Metadata> initMetadata = new ArrayList<>();
        initMetadata.add(delimiter);
        initMetadata.add(quoteCharacter);
        initMetadata.add(escapeCharacter);
        initMetadata.add(headerExistence);

        StructType structType = this.rawData.schema();
        Arrays.stream(structType.fields()).forEach(field -> {
            DataType dataType = field.dataType();
            String fieldName = field.name();
            PropertyDataType propertyDataType = new PropertyDataType(fieldName, de.hpi.isg.dataprep.util.DataType.getTypeFromSparkType(dataType));
            initMetadata.add(propertyDataType);
        });

        this.metadataRepository.updateMetadata(initMetadata);
    }

    @Override
    public void buildMetadataSetup() {
        // build preparation, i.e., call the buildpreparator method of preparator instance to set metadata prerequiste and post-change
        this.preparations.stream().forEachOrdered(preparation -> preparation.getAbstractPreparator().buildMetadataSetup());
    }

    @Override
    public void addRecommendedPreparation() {
        // call the decision engine to collect scores from all preparator candidates, and select the one with the highest score.
        // now the process terminates when the selectBestPreparator method return null.
        AbstractPreparator recommendedPreparator = this.decisionEngine.selectBestPreparator(rawData);

        // Note: the traditional control flow is to add the preparations first and then execute the batch.
        // But in the recommendation mode the preparator is executed immediately after generated so that the datasets, metadata, env
        // can be updated.
        AbstractPreparation preparation = new Preparation(recommendedPreparator);
        preparations.add(preparation);
    }

    @Override
    public void executeRecommendedPreparation() {
        //execute the added preparation

        // updated the dataset, metadata

        // update the schemaMapping
    }

    @Override
    public List<AbstractPreparation> getPreparations() {
        return preparations;
    }

    @Override
    public ErrorRepository getErrorRepository() {
        return errorRepository;
    }

    @Override
    public MetadataRepository getMetadataRepository() {
        return metadataRepository;
    }

    @Override
    public ProvenanceRepository getProvenanceRepository() {
        return provenanceRepository;
    }

    @Override
    public Dataset<Row> getRawData() {
        return rawData;
    }

    @Override
    public void setRawData(Dataset<Row> rawData) {
        this.rawData = rawData;
    }

    @Override
    public String getDatasetName() {
        return this.datasetName;
    }

    @Override
    public Collection<ColumnCombination> getColumnCombinations() {
        // Note: the size of this column combination grows exponentially.
        return this.columnCombinations;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "Pipeline{" +
                "name='" + name + '\'' +
                '}';
    }
}
