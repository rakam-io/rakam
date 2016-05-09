package org.rakam.recipe;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.rakam.analysis.ContinuousQueryService;
import org.rakam.analysis.MaterializedViewService;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.collection.SchemaField;
import org.rakam.ui.DashboardService;
import org.rakam.ui.JDBCReportMetadata;
import org.rakam.ui.customreport.JDBCCustomReportMetadata;
import org.rakam.ui.page.CustomPageDatabase;
import org.rakam.util.AlreadyExistsException;
import org.rakam.util.RakamException;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;

public class RecipeHandler {
    private final Metastore metastore;
    private final ContinuousQueryService continuousQueryService;
    private final MaterializedViewService materializedViewService;
    private final JDBCReportMetadata reportMetadata;
    private final JDBCCustomReportMetadata customReportMetadata;
    private final Optional<CustomPageDatabase> customPageDatabase;
    private final DashboardService dashboardService;

    @Inject
    public RecipeHandler(Metastore metastore, ContinuousQueryService continuousQueryService,
                         MaterializedViewService materializedViewService,
                         JDBCCustomReportMetadata customReportMetadata,
                         Optional<CustomPageDatabase> customPageDatabase,
                         DashboardService dashboardService,
                         JDBCReportMetadata reportMetadata) {
        this.metastore = metastore;
        this.materializedViewService = materializedViewService;
        this.continuousQueryService = continuousQueryService;
        this.customReportMetadata = customReportMetadata;
        this.customPageDatabase = customPageDatabase;
        this.reportMetadata = reportMetadata;
        this.dashboardService = dashboardService;
    }

    public Recipe export(String project) {
        final Map<String, Recipe.Collection> collections = metastore.getCollections(project).entrySet().stream().collect(Collectors.toMap(e -> e.getKey(), e -> {
            List<Map<String, Recipe.SchemaFieldInfo>> map = e.getValue().stream()
                    .map(a -> ImmutableMap.of(a.getName(), new Recipe.SchemaFieldInfo(a.getCategory(), a.getType())))
                    .collect(Collectors.toList());
            return new Recipe.Collection(map);
        }));
        final List<Recipe.MaterializedViewBuilder> materializedViews = materializedViewService.list(project).stream()
                .map(m -> new Recipe.MaterializedViewBuilder(m.name, m.tableName, m.query, m.updateInterval, m.incremental))
                .collect(Collectors.toList());
        final List<Recipe.ContinuousQueryBuilder> continuousQueryBuilders = continuousQueryService.list(project).stream()
                .map(m -> new Recipe.ContinuousQueryBuilder(m.name, m.tableName, m.query, m.partitionKeys, m.options))
                .collect(Collectors.toList());

        final List<Recipe.ReportBuilder> reports = reportMetadata
                .getReports(null, project).stream()
                .map(r -> new Recipe.ReportBuilder(r.slug, r.name, r.category, r.query, r.options, r.shared))
                .collect(Collectors.toList());

        final List<Recipe.CustomReportBuilder> customReports = customReportMetadata
                .list(project).entrySet().stream().flatMap(a -> a.getValue().stream())
                .map(r -> new Recipe.CustomReportBuilder(r.reportType, r.name, r.data))
                .collect(Collectors.toList());

        final List<Recipe.CustomPageBuilder> customPages;
        if(customPageDatabase.isPresent()) {
            customPages = customPageDatabase.get()
                    .list(project).stream()
                    .map(r -> new Recipe.CustomPageBuilder(r.name, r.slug, r.category, customPageDatabase.get().get(project, r.slug)))
                    .collect(Collectors.toList());
        } else {
            customPages = ImmutableList.of();
        }

        List<Recipe.DashboardBuilder> dashboards = dashboardService.list(project).stream()
                .map(a -> new Recipe.DashboardBuilder(a.name, dashboardService.get(project, a.name)))
                .collect(Collectors.toList());

        return new Recipe(Recipe.Strategy.SPECIFIC, project, collections, materializedViews,
                continuousQueryBuilders, customReports, customPages, dashboards, reports);
    }

    public void install(Recipe recipe, String project, boolean overrideExisting) {
        installInternal(recipe, project, overrideExisting);
    }

    public void install(Recipe recipe, boolean overrideExisting) {
        if (recipe.getProject() != null) {
            installInternal(recipe, recipe.getProject(), overrideExisting);
        } else {
            throw new IllegalArgumentException("project is null");
        }
    }

    public void installInternal(Recipe recipe, String project, boolean overrideExisting) {
        recipe.getCollections().forEach((collectionName, collection) -> {
            List<SchemaField> build = collection.build();
            List<SchemaField> fields = metastore.getOrCreateCollectionFieldList(project, collectionName,
                    ImmutableSet.copyOf(build));
            List<SchemaField> collisions = build.stream()
                    .filter(f -> fields.stream().anyMatch(field -> field.getName().equals(f.getName()) && !f.getType().equals(field.getType())))
                    .collect(Collectors.toList());

            if (!collisions.isEmpty()) {
                String errMessage = collisions.stream().map(f -> {
                    SchemaField existingField = fields.stream().filter(field -> field.getName().equals(f.getName())).findAny().get();
                    return String.format("Recipe: [%s : %s], Collection: [%s, %s]", f.getName(), f.getType(),
                            existingField.getName(), existingField.getType());
                }).collect(Collectors.joining(", "));
                String message = overrideExisting ? "Overriding collection fields is not possible." : "Collision in collection fields.";
                throw new RakamException(message + " " + errMessage, BAD_REQUEST);
            }
        });

        recipe.getContinuousQueryBuilders().stream()
                .map(builder -> builder.createContinuousQuery(project))
                .forEach(continuousQuery -> continuousQueryService.create(project, continuousQuery, false).getResult().whenComplete((res, ex) -> {
                    if (ex != null) {
                        if (ex instanceof AlreadyExistsException) {
                            if (overrideExisting) {
                                continuousQueryService.delete(project, continuousQuery.tableName);
                                continuousQueryService.create(project, continuousQuery, false);
                            } else {
                                throw Throwables.propagate(ex);
                            }
                        }
                        throw Throwables.propagate(ex);
                    }
                }));

        recipe.getMaterializedViewBuilders().stream()
                .map(builder -> builder.createMaterializedView(project))
                .forEach(materializedView -> materializedViewService.create(project, materializedView).whenComplete((res, ex) -> {
                    if (ex != null) {
                        if (ex instanceof AlreadyExistsException) {
                            if (overrideExisting) {
                                materializedViewService.delete(project, materializedView.tableName);
                                materializedViewService.create(project, materializedView);
                            } else {
                                throw Throwables.propagate(ex);
                            }
                        }
                        throw Throwables.propagate(ex);
                    }
                }));

        recipe.getReports().stream()
                .map(reportBuilder -> reportBuilder.createReport(project))
                .forEach(report -> {
                    try {
                        reportMetadata.save(null, project, report);
                    } catch (AlreadyExistsException e) {
                        if (overrideExisting) {
                            reportMetadata.update(null, project, report);
                        } else {
                            throw Throwables.propagate(e);
                        }
                    }
                });

        recipe.getDashboards().stream()
                .forEach(report -> {
                    int dashboard;
                    try {
                        dashboard = dashboardService.create(project, report.name, ImmutableMap.of()).id;
                    } catch (AlreadyExistsException e) {
                        dashboard = dashboardService.list(project).stream().filter(a -> a.name.equals(report.name)).findAny().get().id;
                        dashboardService.delete(project, dashboard);
                        dashboard = dashboardService.create(project, report.name, ImmutableMap.of()).id;
                    }

                    for (DashboardService.DashboardItem item : report.items) {
                        dashboardService.addToDashboard(project, dashboard, item.name, item.directive, item.data);
                    }
                });

        recipe.getCustomReports().stream()
                .map(reportBuilder -> reportBuilder.createCustomReport(project))
                .forEach(customReport -> {
                    try {
                        customReportMetadata.save(null, project, customReport);
                    } catch (AlreadyExistsException e) {
                        if (overrideExisting) {
                            customReportMetadata.update(project, customReport);
                        } else {
                            throw Throwables.propagate(e);
                        }
                    }
                });

        if(customPageDatabase.isPresent()) {
            recipe.getCustomPages().stream()
                    .map(reportBuilder -> reportBuilder.createCustomPage(project))
                    .forEach(customReport -> {
                        try {
                            customPageDatabase.get().save(null, project, customReport);
                        } catch (AlreadyExistsException e) {
                            if (overrideExisting) {
                                customPageDatabase.get().delete(project, customReport.slug);
                                customPageDatabase.get().save(null, project, customReport);
                            } else {
                                throw Throwables.propagate(e);
                            }
                        }
                    });
        } else
        if(recipe.getCustomPages().size() > 0) {
            throw new RakamException("Custom page feature is not supported", BAD_REQUEST);
        }
    }
}
