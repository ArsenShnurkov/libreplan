/*
 * This file is part of ###PROJECT_NAME###
 *
 * Copyright (C) 2009 Fundación para o Fomento da Calidade Industrial e
 *                    Desenvolvemento Tecnolóxico de Galicia
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.navalplanner.web.planner;

import static org.navalplanner.web.I18nHelper._;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.joda.time.LocalDate;
import org.navalplanner.business.calendars.entities.ResourceCalendar;
import org.navalplanner.business.common.exceptions.InstanceNotFoundException;
import org.navalplanner.business.orders.daos.IOrderDAO;
import org.navalplanner.business.orders.entities.Order;
import org.navalplanner.business.planner.entities.DayAssignment;
import org.navalplanner.business.planner.entities.TaskElement;
import org.navalplanner.business.planner.entities.TaskMilestone;
import org.navalplanner.business.resources.daos.IResourceDAO;
import org.navalplanner.business.resources.entities.Resource;
import org.navalplanner.web.common.ViewSwitcher;
import org.navalplanner.web.planner.allocation.ResourceAllocationController;
import org.navalplanner.web.servlets.CallbackServlet;
import org.navalplanner.web.servlets.CallbackServlet.IServletRequestHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.zkforge.timeplot.Plotinfo;
import org.zkforge.timeplot.Timeplot;
import org.zkforge.timeplot.data.PlotDataSource;
import org.zkforge.timeplot.geometry.DefaultValueGeometry;
import org.zkforge.timeplot.geometry.ValueGeometry;
import org.zkoss.ganttz.Planner;
import org.zkoss.ganttz.adapters.IStructureNavigator;
import org.zkoss.ganttz.adapters.PlannerConfiguration;
import org.zkoss.ganttz.timetracker.TimeTracker;
import org.zkoss.ganttz.timetracker.zoom.IZoomLevelChangedListener;
import org.zkoss.ganttz.timetracker.zoom.ZoomLevel;
import org.zkoss.ganttz.util.Interval;

/**
 * @author Óscar González Fernández <ogonzalez@igalia.com>
 */
@Component
@Scope(BeanDefinition.SCOPE_SINGLETON)
public abstract class OrderPlanningModel implements IOrderPlanningModel {

    @Autowired
    private IOrderDAO orderDAO;

    private PlanningState planningState;

    @Autowired
    private IResourceDAO resourceDAO;

    private Integer maximunValueForChart = 0;

    private final class TaskElementNavigator implements
            IStructureNavigator<TaskElement> {
        @Override
        public List<TaskElement> getChildren(TaskElement object) {
            return object.getChildren();
        }

        @Override
        public boolean isLeaf(TaskElement object) {
            return object.isLeaf();
        }

        @Override
        public boolean isMilestone(TaskElement object) {
            if (object != null) {
                return object instanceof TaskMilestone;
            }
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void setConfigurationToPlanner(Planner planner, Order order,
            ViewSwitcher switcher,
            ResourceAllocationController resourceAllocationController,
            EditTaskController editTaskController,
            SplittingController splittingController,
            CalendarAllocationController calendarAllocationController) {
        Order orderReloaded = reload(order);
        if (!orderReloaded.isSomeTaskElementScheduled())
            throw new IllegalArgumentException(_(
                    "The order {0} must be scheduled", orderReloaded));
        PlannerConfiguration<TaskElement> configuration = createConfiguration(orderReloaded);

        configuration.addGlobalCommand(buildSaveCommand());

        configuration.addCommandOnTask(buildResourceAllocationCommand(resourceAllocationController));
        configuration.addCommandOnTask(buildSplitCommand(splittingController));
        configuration.addCommandOnTask(buildMergeTaskCommand());
        configuration.addCommandOnTask(buildMilestoneCommand());
        configuration
                .addCommandOnTask(buildCalendarAllocationCommand(calendarAllocationController));

        configuration.setEditTaskCommand(buildEditTaskCommand(editTaskController));

        Timeplot chartComponent = new Timeplot();
        configuration.setChartComponent(chartComponent);

        planner.setConfiguration(configuration);

        setupChart(orderReloaded, chartComponent, planner.getTimeTracker());
    }

    private ICalendarAllocationCommand buildCalendarAllocationCommand(
            CalendarAllocationController calendarAllocationController) {
        ICalendarAllocationCommand calendarAllocationCommand = getCalendarAllocationCommand();
        calendarAllocationCommand
                .setCalendarAllocationController(calendarAllocationController);
        return calendarAllocationCommand;
    }

    private IEditTaskCommand buildEditTaskCommand(
            EditTaskController editTaskController) {
        IEditTaskCommand editTaskCommand = getEditTaskCommand();
        editTaskCommand.setEditTaskController(editTaskController);
        return editTaskCommand;
    }

    private IAddMilestoneCommand buildMilestoneCommand() {
        IAddMilestoneCommand addMilestoneCommand = getAddMilestoneCommand();
        addMilestoneCommand.setState(planningState);
        return addMilestoneCommand;
    }

    private IMergeTaskCommand buildMergeTaskCommand() {
        IMergeTaskCommand mergeCommand = getMergeTaskCommand();
        mergeCommand.setState(planningState);
        return mergeCommand;
    }

    private ISplitTaskCommand buildSplitCommand(
            SplittingController splittingController) {
        ISplitTaskCommand splitCommand = getSplitCommand();
        splitCommand.setState(planningState);
        splitCommand.setSplitWindowController(splittingController);
        return splitCommand;
    }

    private IResourceAllocationCommand buildResourceAllocationCommand(
            ResourceAllocationController resourceAllocationController) {
        IResourceAllocationCommand resourceAllocationCommand = getResourceAllocationCommand();
        resourceAllocationCommand.initialize(resourceAllocationController,
                planningState);
        return resourceAllocationCommand;
    }

    private ISaveCommand buildSaveCommand() {
        ISaveCommand saveCommand = getSaveCommand();
        saveCommand.setState(planningState);
        return saveCommand;
    }

    private void setupChart(Order orderReloaded, Timeplot chartComponent,
            TimeTracker timeTracker) {
        fillChart(orderReloaded, chartComponent, timeTracker
                .getRealInterval(), timeTracker.getHorizontalSize());
        fillChartOnZoomChange(orderReloaded, chartComponent, timeTracker);
    }

    private IZoomLevelChangedListener zoomListener;

    private void fillChartOnZoomChange(final Order order,
            final Timeplot chartComponent, final TimeTracker timeTracker) {

        zoomListener = new IZoomLevelChangedListener() {

            @Override
            public void zoomLevelChanged(ZoomLevel detailLevel) {
                fillChart(order, chartComponent, timeTracker.getRealInterval(),
                        timeTracker.getHorizontalSize());
            }
        };

        timeTracker.addZoomListener(zoomListener);
    }

    private PlannerConfiguration<TaskElement> createConfiguration(
            Order orderReloaded) {
        ITaskElementAdapter taskElementAdapter = getTaskElementAdapter();
        taskElementAdapter.setOrder(orderReloaded);
        planningState = new PlanningState(retainOnlyTopLevel(orderReloaded
                .getAssociatedTasks()), resourceDAO.list(Resource.class));
        forceLoadOfDependenciesCollections(planningState.getInitial());
        forceLoadOfWorkingHours(planningState.getInitial());
        forceLoadOfLabels(planningState.getInitial());
        return new PlannerConfiguration<TaskElement>(taskElementAdapter,
                new TaskElementNavigator(), planningState.getInitial());
    }

    private Collection<? extends TaskElement> retainOnlyTopLevel(
            List<TaskElement> associatedTasks) {
        Set<TaskElement> descendantsFromOther = new HashSet<TaskElement>();
        for (TaskElement taskElement : associatedTasks) {
            descandants(descendantsFromOther, taskElement);
        }
        ArrayList<TaskElement> result = new ArrayList<TaskElement>();
        for (TaskElement taskElement : associatedTasks) {
            if (!descendantsFromOther.contains(taskElement)) {
                result.add(taskElement);
            }
        }
        return result;
    }

    private void descandants(Set<TaskElement> accumulated,
            TaskElement taskElement) {
        if (taskElement.isLeaf()) {
            return;
        }
        for (TaskElement t : taskElement.getChildren()) {
            accumulated.add(t);
            descandants(accumulated, t);
        }
    }

    private void forceLoadOfWorkingHours(List<TaskElement> initial) {
        for (TaskElement taskElement : initial) {
            taskElement.getOrderElement().getWorkHours();
            if (!taskElement.isLeaf()) {
                forceLoadOfWorkingHours(taskElement.getChildren());
            }
        }
    }

    private void forceLoadOfDependenciesCollections(
            Collection<? extends TaskElement> elements) {
        for (TaskElement task : elements) {
            forceLoadOfDepedenciesCollections(task);
            if (!task.isLeaf()) {
                forceLoadOfDependenciesCollections(task.getChildren());
            }
        }
    }

    private void forceLoadOfDepedenciesCollections(TaskElement task) {
        task.getDependenciesWithThisOrigin().size();
        task.getDependenciesWithThisDestination().size();
    }

    private void forceLoadOfLabels(List<TaskElement> initial) {
        for (TaskElement taskElement : initial) {
            if (taskElement.isLeaf()) {
                taskElement.getOrderElement().getLabels().size();
            } else {
                forceLoadOfLabels(taskElement.getChildren());
            }
        }
    }

    // spring method injection
    protected abstract ITaskElementAdapter getTaskElementAdapter();

    protected abstract ISaveCommand getSaveCommand();

    protected abstract IResourceAllocationCommand getResourceAllocationCommand();

    protected abstract ISplitTaskCommand getSplitCommand();

    protected abstract IMergeTaskCommand getMergeTaskCommand();

    protected abstract IAddMilestoneCommand getAddMilestoneCommand();

    protected abstract IEditTaskCommand getEditTaskCommand();

    protected abstract ICalendarAllocationCommand getCalendarAllocationCommand();

    private Order reload(Order order) {
        try {
            return orderDAO.find(order.getId());
        } catch (InstanceNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private void fillChart(Order order, Timeplot chart, Interval interval,
            Integer size) {
        chart.getChildren().clear();
        maximunValueForChart = 0;

        Plotinfo plotInfoOrder = getLoadPlotInfo(order, interval.getStart(),
                interval.getFinish());
        plotInfoOrder.setFillColor("0000FF");

        Plotinfo plotInfoCompany = getResourcesLoadPlotInfo(order, interval
                .getStart(), interval.getFinish());
        plotInfoCompany.setFillColor("00FF00");

        Plotinfo plotInfoMax = getCalendarMaximumAvailabilityPlotInfo(order,
                interval.getStart(), interval.getFinish());
        plotInfoMax.setLineColor("FF0000");

        ValueGeometry valueGeometry = new DefaultValueGeometry();
        valueGeometry.setMin(0);
        valueGeometry.setMax(maximunValueForChart);
        valueGeometry.setGridColor("#000000");
        valueGeometry.setAxisLabelsPlacement("left");

        plotInfoOrder.setValueGeometry(valueGeometry);
        plotInfoCompany.setValueGeometry(valueGeometry);
        plotInfoMax.setValueGeometry(valueGeometry);

        chart.appendChild(plotInfoMax);
        chart.appendChild(plotInfoOrder);
        chart.appendChild(plotInfoCompany);

        size = size + (16 * 2);
        chart.setWidth(size + "px");
        chart.setHeight("200px");
    }

    private Plotinfo getLoadPlotInfo(Order order, Date start, Date finish) {
        List<DayAssignment> dayAssignments = order.getDayAssignments();
        SortedMap<LocalDate, Integer> mapDayAssignments = calculateHoursAdditionByDay(dayAssignments);

        String uri = getServletUri(mapDayAssignments, start, finish);

        PlotDataSource pds = new PlotDataSource();
        pds.setDataSourceUri(uri);
        pds.setSeparator(" ");

        Plotinfo plotInfo = new Plotinfo();
        plotInfo.setPlotDataSource(pds);

        return plotInfo;
    }

    private void printLine(PrintWriter writer, LocalDate day, Integer hours) {
        writer.println(day.toString("yyyyMMdd") + " " + hours);
    }

    private void fillZeroValueFromStart(PrintWriter writer, Date start,
            SortedMap<LocalDate, Integer> mapDayAssignments) {
        LocalDate day = new LocalDate(start);
        if (mapDayAssignments.isEmpty()) {
            printLine(writer, day, 0);
        } else if (day.compareTo(mapDayAssignments.firstKey()) < 0) {
            printLine(writer, day, 0);
            printLine(writer, mapDayAssignments.firstKey().minusDays(1), 0);
        }
    }

    private void fillZeroValueToFinish(PrintWriter writer, Date finish,
            SortedMap<LocalDate, Integer> mapDayAssignments) {
        LocalDate day = new LocalDate(finish);
        if (mapDayAssignments.isEmpty()) {
            printLine(writer, day, 0);
        } else if (day.compareTo(mapDayAssignments.lastKey()) > 0) {
            printLine(writer, mapDayAssignments.lastKey().plusDays(1), 0);
            printLine(writer, day, 0);
        }
    }

    private Plotinfo getResourcesLoadPlotInfo(Order order, Date start,
            Date finish) {
        List<DayAssignment> dayAssignments = new ArrayList<DayAssignment>();

        Set<Resource> resources = order.getResources();
        for (Resource resource : resources) {
            dayAssignments.addAll(resource.getAssignments());
        }

        SortedMap<LocalDate, Integer> mapDayAssignments = calculateHoursAdditionByDay(dayAssignments);

        String uri = getServletUri(mapDayAssignments, start, finish);

        PlotDataSource pds = new PlotDataSource();
        pds.setDataSourceUri(uri);
        pds.setSeparator(" ");

        Plotinfo plotInfo = new Plotinfo();
        plotInfo.setPlotDataSource(pds);

        return plotInfo;
    }

    private Plotinfo getCalendarMaximumAvailabilityPlotInfo(Order order,
            Date start, Date finish) {
        SortedMap<LocalDate, Integer> mapDayAssignments = calculateHoursAdditionByDay(
                order.getDayAssignments(), true);

        String uri = getServletUri(mapDayAssignments, start, finish);

        PlotDataSource pds = new PlotDataSource();
        pds.setDataSourceUri(uri);
        pds.setSeparator(" ");

        Plotinfo plotInfo = new Plotinfo();
        plotInfo.setPlotDataSource(pds);

        return plotInfo;
    }

    private String getServletUri(
            final SortedMap<LocalDate, Integer> mapDayAssignments,
            final Date start, final Date finish) {
        setMaximunValueForChartIfGreater(Collections.max(mapDayAssignments.values()));

        String uri = CallbackServlet
                .registerAndCreateURLFor(new IServletRequestHandler() {

                    @Override
                    public void handle(HttpServletRequest request,
                            HttpServletResponse response)
                            throws ServletException, IOException {
                        PrintWriter writer = response.getWriter();

                        fillZeroValueFromStart(writer, start, mapDayAssignments);

                        for (LocalDate day : mapDayAssignments.keySet()) {
                            Integer hours = mapDayAssignments.get(day);
                            printLine(writer, day, hours);
                        }

                        fillZeroValueToFinish(writer, finish, mapDayAssignments);

                        writer.close();
                    }
                });
        return uri;
    }

    private SortedMap<LocalDate, Integer> calculateHoursAdditionByDay(
            List<DayAssignment> dayAssignments) {
        return calculateHoursAdditionByDay(dayAssignments, false);
    }

    /**
     * Calculate the hours by day for all the {@link DayAssignment} in the list.
     *
     * @param dayAssignments
     *            The list of {@link DayAssignment}
     * @param calendarHours
     *            If <code>true</code> the resource's calendar will be used to
     *            calculate the available hours. Otherwise, the
     *            {@link DayAssignment} hours will be used.
     * @return A map { day => hours } sorted by date
     */
    private SortedMap<LocalDate, Integer> calculateHoursAdditionByDay(
            List<DayAssignment> dayAssignments, boolean calendarHours) {
        SortedMap<LocalDate, Integer> map = new TreeMap<LocalDate, Integer>();

        if (dayAssignments.isEmpty()) {
            return map;
        }

        Collections.sort(dayAssignments, new Comparator<DayAssignment>() {

            @Override
            public int compare(DayAssignment o1, DayAssignment o2) {
                return o1.getDay().compareTo(o2.getDay());
            }

        });

        for (DayAssignment dayAssignment : dayAssignments) {
            LocalDate day = dayAssignment.getDay();
            Integer hours = 0;

            if (calendarHours) {
                ResourceCalendar calendar = dayAssignment.getResource()
                        .getCalendar();
                if (calendar != null) {
                    hours = calendar.getWorkableHours(dayAssignment.getDay());
                }
            } else {
                hours = dayAssignment.getHours();
            }

            if (map.get(day) == null) {
                map.put(day, hours);
            } else {
                map.put(day, map.get(day) + hours);
            }
        }

        return map;
    }

    private void setMaximunValueForChartIfGreater(Integer max) {
        if (maximunValueForChart < max) {
            maximunValueForChart = max;
        }
    }

}
