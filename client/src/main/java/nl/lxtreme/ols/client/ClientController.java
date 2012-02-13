/*
 * OpenBench LogicSniffer / SUMP project 
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110, USA
 *
 * 
 * Copyright (C) 2010-2011 - J.W. Janssen, http://www.lxtreme.nl
 */
package nl.lxtreme.ols.client;


import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.logging.*;

import javax.swing.*;
import nl.lxtreme.ols.api.*;
import nl.lxtreme.ols.api.acquisition.*;
import nl.lxtreme.ols.api.data.*;
import nl.lxtreme.ols.api.data.Cursor;
import nl.lxtreme.ols.api.data.annotation.Annotation;
import nl.lxtreme.ols.api.data.annotation.AnnotationListener;
import nl.lxtreme.ols.api.data.export.*;
import nl.lxtreme.ols.api.data.project.*;
import nl.lxtreme.ols.api.devices.*;
import nl.lxtreme.ols.api.tools.*;
import nl.lxtreme.ols.api.ui.*;
import nl.lxtreme.ols.client.action.*;
import nl.lxtreme.ols.client.actionmanager.*;
import nl.lxtreme.ols.client.diagram.settings.*;
import nl.lxtreme.ols.client.signaldisplay.*;
import nl.lxtreme.ols.util.*;
import nl.lxtreme.ols.util.swing.*;
import nl.lxtreme.ols.util.swing.component.*;

import org.osgi.framework.*;


/**
 * Denotes a front-end controller for the client.
 */
public final class ClientController implements ActionProvider, AcquisitionProgressListener, AcquisitionStatusListener,
    AcquisitionDataListener, AnnotationListener, ApplicationCallback
{
  // INNER TYPES

  /**
   * Provides a {@link AccumulatingRunnable} that repaints the entire main frame
   * once in a while. This is necessary if a tool produces lots of annotations
   * in a short time-frame, which would otherwise cause the UI to become slow
   * due to the many repaint requests.
   */
  final class AccumulatingRepaintingRunnable extends AccumulatingRunnable<Void>
  {
    /**
     * {@inheritDoc}
     */
    @Override
    protected void run( final Deque<Void> aArgs )
    {
      repaintMainFrame();
    }
  }

  /**
   * Provides an {@link Action} for closing a {@link JOptionPane}.
   */
  static final class CloseOptionPaneAction extends AbstractAction
  {
    private static final long serialVersionUID = 1L;

    /**
     * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
     */
    @Override
    public void actionPerformed( final ActionEvent aEvent )
    {
      final JOptionPane optionPane = ( JOptionPane )aEvent.getSource();
      optionPane.setValue( Integer.valueOf( JOptionPane.CLOSED_OPTION ) );
    }
  }

  /**
   * Provides a hack to ensure the system class loader is used at all times when
   * loading UI classes/resources/...
   */
  static class CLValue implements UIDefaults.ActiveValue
  {
    /**
     * @see javax.swing.UIDefaults.ActiveValue#createValue(javax.swing.UIDefaults)
     */
    public @Override
    ClassLoader createValue( final UIDefaults aDefaults )
    {
      return Activator.class.getClassLoader();
    }
  }

  /**
   * Provides a default tool context implementation.
   */
  static final class DefaultToolContext implements ToolContext
  {
    // VARIABLES

    private final DataSet dataSet;
    private final int startSampleIdx;
    private final int endSampleIdx;

    // CONSTRUCTORS

    /**
     * Creates a new DefaultToolContext instance.
     * 
     * @param aStartSampleIdx
     *          the starting sample index;
     * @param aEndSampleIdx
     *          the ending sample index;
     * @param aData
     *          the acquisition result.
     */
    public DefaultToolContext( final int aStartSampleIdx, final int aEndSampleIdx, final DataSet aDataSet )
    {
      this.startSampleIdx = aStartSampleIdx;
      this.endSampleIdx = aEndSampleIdx;
      this.dataSet = aDataSet;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getChannels()
    {
      return getData().getChannels();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Cursor getCursor( final int aIndex )
    {
      return this.dataSet.getCursor( aIndex );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AcquisitionResult getData()
    {
      return this.dataSet.getCapturedData();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getEnabledChannels()
    {
      return getData().getEnabledChannels();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getEndSampleIndex()
    {
      return this.endSampleIdx;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getLength()
    {
      return Math.max( 0, this.endSampleIdx - this.startSampleIdx );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getStartSampleIndex()
    {
      return this.startSampleIdx;
    }
  }

  /**
   * A runnable implementation that accumulates several calls to avoid an
   * avalanche of events on the EDT.
   */
  final class ProgressUpdatingRunnable extends AccumulatingRunnable<Integer>
  {
    /**
     * {@inheritDoc}
     */
    @Override
    protected void run( final Deque<Integer> aArgs )
    {
      final Integer percentage = aArgs.getLast();
      setProgressOnEDT( percentage.intValue() );
      updateActionsOnEDT();
    }
  }

  // CONSTANTS

  private static final Logger LOG = Logger.getLogger( ClientController.class.getName() );

  // VARIABLES

  private final BundleContext bundleContext;
  private final ActionManager actionManager;
  private final SignalDiagramController signalDiagramController;

  private final List<Device> devices;
  private final List<Tool<?>> tools;
  private final List<Exporter> exporters;

  private final ProgressUpdatingRunnable progressAccumulatingRunnable;
  private final AccumulatingRepaintingRunnable repaintAccumulatingRunnable;

  private volatile ProjectManager projectManager;
  private volatile DataAcquisitionService dataAcquisitionService;
  private volatile MainFrame mainFrame;
  private volatile HostProperties hostProperties;

  // CONSTRUCTORS

  /**
   * Creates a new ClientController instance.
   * 
   * @param aBundleContext
   *          the bundle context to use for interaction with the OSGi framework;
   * @param aHost
   *          the current host to use, cannot be <code>null</code>;
   * @param aProjectManager
   *          the project manager to use, cannot be <code>null</code>.
   */
  public ClientController( final BundleContext aBundleContext )
  {
    this.bundleContext = aBundleContext;

    this.devices = new ArrayList<Device>();
    this.tools = new ArrayList<Tool<?>>();
    this.exporters = new ArrayList<Exporter>();

    this.actionManager = ActionManagerFactory.createActionManager();
    this.signalDiagramController = new SignalDiagramController( this.actionManager );

    ActionManagerFactory.fillActionManager( this.actionManager, this );

    this.progressAccumulatingRunnable = new ProgressUpdatingRunnable();
    this.repaintAccumulatingRunnable = new AccumulatingRepaintingRunnable();
  }

  // METHODS

  /**
   * {@inheritDoc}
   */
  @Override
  public void acquisitionComplete( final AcquisitionResult aData )
  {
    getCurrentProject().setCapturedData( aData );

    updateActionsOnEDT();
    restoreZoomLevel();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void acquisitionEnded( final AcquisitionResultStatus aStatus )
  {
    if ( aStatus.isAborted() )
    {
      setStatusOnEDT( "Capture aborted! {0}", aStatus.getMessage() );
    }
    else if ( aStatus.isFailed() )
    {
      setStatusOnEDT( "Capture failed! {0}", aStatus.getMessage() );
    }
    else
    {
      setStatusOnEDT( "Capture finished at {0,date,medium} {0,time,medium}.", new Date() );
    }

    updateActionsOnEDT();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void acquisitionInProgress( final int aPercentage )
  {
    this.progressAccumulatingRunnable.add( Integer.valueOf( aPercentage ) );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void acquisitionStarted()
  {
    updateActionsOnEDT();
  }

  /**
   * Adds a given device to this controller.
   * <p>
   * This method is called by the dependency manager.
   * </p>
   * 
   * @param aDevice
   *          the device to add, cannot be <code>null</code>.
   */
  public void addDevice( final Device aDevice )
  {
    synchronized ( this.devices )
    {
      this.devices.add( aDevice );

      this.actionManager.add( new SelectDeviceAction( this, aDevice.getName() ) );
    }
  }

  /**
   * Adds a given exporter to this controller.
   * <p>
   * This method is called by the dependency manager.
   * </p>
   * 
   * @param aExporter
   *          the exporter to add, cannot be <code>null</code>.
   */
  public void addExporter( final Exporter aExporter )
  {
    synchronized ( this.exporters )
    {
      this.exporters.add( aExporter );

      this.actionManager.add( new ExportAction( this, aExporter.getName() ) );
    }
  }

  /**
   * Adds the given component provider to this controller, and does this
   * synchronously on the EDT.
   * <p>
   * This method is called by the Dependency Manager.
   * </p>
   * 
   * @param aProvider
   *          the component provider, cannot be <code>null</code>.
   */
  public void addMenu( final ComponentProvider aProvider )
  {
    SwingComponentUtils.invokeOnEDT( new Runnable()
    {
      @Override
      public void run()
      {
        final JMenuBar menuBar = getMainMenuBar();

        if ( menuBar != null )
        {
          final JMenu menu = ( JMenu )aProvider.getComponent();
          menuBar.add( menu );

          aProvider.addedToContainer();

          menuBar.revalidate();
          menuBar.repaint();
        }
      }
    } );
  }

  /**
   * Adds a given tool to this controller.
   * <p>
   * This method is called by the dependency manager.
   * </p>
   * 
   * @param aTool
   *          the tool to add, cannot be <code>null</code>.
   */
  public void addTool( final Tool<?> aTool )
  {
    synchronized ( this.tools )
    {
      this.tools.add( aTool );

      this.actionManager.add( new RunToolAction( this, aTool.getName() ) );
    }
  }

  /**
   * @see nl.lxtreme.ols.client.IClientController#cancelCapture()
   */
  public void cancelCapture()
  {
    final DataAcquisitionService acquisitionService = getDataAcquisitionService();
    final Device device = getDevice();
    if ( ( device == null ) || ( acquisitionService == null ) )
    {
      return;
    }

    try
    {
      acquisitionService.cancelAcquisition( device );
    }
    catch ( final IllegalStateException exception )
    {
      setStatusOnEDT( "No acquisition in progress!" );
    }
    catch ( final IOException exception )
    {
      setStatusOnEDT( "I/O problem: " + exception.getMessage() );

      // Make sure to handle IO-interrupted exceptions properly!
      if ( !HostUtils.handleInterruptedException( exception ) )
      {
        exception.printStackTrace();
      }
    }
    finally
    {
      updateActionsOnEDT();
    }
  }

  /**
   * {@inheritDoc}
   */
  public boolean captureData( final Window aParent )
  {
    final DataAcquisitionService acquisitionService = getDataAcquisitionService();
    final Device device = getDevice();
    if ( ( device == null ) || ( acquisitionService == null ) )
    {
      return false;
    }

    try
    {
      if ( device.setupCapture( aParent ) )
      {
        setStatusOnEDT( "Capture from {0} started at {1,date,medium} {1,time,medium} ...", device.getName(), new Date() );

        acquisitionService.acquireData( device );
        return true;
      }

      return false;
    }
    catch ( final IOException exception )
    {
      setStatusOnEDT( "I/O problem: " + exception.getMessage() );

      // Make sure to handle IO-interrupted exceptions properly!
      if ( !HostUtils.handleInterruptedException( exception ) )
      {
        exception.printStackTrace();
      }

      return false;
    }
    finally
    {
      updateActionsOnEDT();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void clearAnnotations()
  {
    for ( Channel channel : getCurrentDataSet().getChannels() )
    {
      channel.clearAnnotations();
    }

    repaintMainFrame();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void clearAnnotations( final int aChannelIdx )
  {
    final Channel channel = getChannel( aChannelIdx );
    channel.clearAnnotations();

    repaintMainFrame();
  }

  /**
   * Creates a new project, causing all current data to be thrown away.
   */
  public void createNewProject()
  {
    this.projectManager.createNewProject();

    if ( this.mainFrame != null )
    {
      this.mainFrame.repaint();
    }

    updateActionsOnEDT();
  }

  /**
   * Terminates & shuts down the client.
   */
  public void exit()
  {
    // Close the main window ourselves; we should do this explicitly...
    if ( this.mainFrame != null )
    {
      this.mainFrame.setVisible( false );
      this.mainFrame.dispose();
      this.mainFrame = null;
    }

    try
    {
      // Stop the framework bundle; which should stop all other bundles as
      // well; the STOP_TRANSIENT option ensures the bundle is restarted the
      // next time...
      this.bundleContext.getBundle( 0 ).stop( Bundle.STOP_TRANSIENT );
    }
    catch ( final IllegalStateException ex )
    {
      LOG.warning( "Bundle context no longer valid while shutting down client?!" );

      // The bundle context is no longer valid; we're going to exit anyway, so
      // lets ignore this exception for now...
      System.exit( -1 );
    }
    catch ( final BundleException be )
    {
      LOG.warning( "Bundle context no longer valid while shutting down client?!" );

      System.exit( -1 );
    }
  }

  /**
   * Exports the current data set to a file using an {@link Exporter} with a
   * given name.
   * 
   * @param aExporterName
   *          the name of the exporter to use, cannot be <code>null</code>;
   * @param aExportFile
   *          the file to export the results to, cannot be <code>null</code>.
   * @throws IOException
   *           in case of I/O problems during the export.
   */
  public void exportTo( final String aExporterName, final File aExportFile ) throws IOException
  {
    if ( this.mainFrame == null )
    {
      return;
    }

    OutputStream writer = null;

    try
    {
      writer = new FileOutputStream( aExportFile );

      final Exporter exporter = getExporter( aExporterName );
      exporter.export( getCurrentDataSet(), this.mainFrame.getDiagramScrollPane(), writer );

      setStatusOnEDT( "Export to {0} succesful ...", aExporterName );
    }
    finally
    {
      HostUtils.closeResource( writer );

      updateActionsOnEDT();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Action getAction( final String aID )
  {
    return this.actionManager.getAction( aID );
  }

  /**
   * Provides direct access to the cursor with the given index.
   * 
   * @param aCursorIdx
   *          the index of the cursor, >= 0 && < {@link Ols#MAX_CURSORS}.
   * @return a cursor, never <code>null</code>.
   */
  public final Cursor getCursor( final int aCursorIdx )
  {
    final DataSet currentDataSet = getCurrentDataSet();
    if ( currentDataSet == null )
    {
      return null;
    }
    return currentDataSet.getCursor( aCursorIdx );
  }

  /**
   * Returns the current selected device.
   * 
   * @return the selected device, can be <code>null</code> if no device is
   *         selected.
   */
  public final Device getDevice()
  {
    if ( this.mainFrame != null )
    {
      final String selectedDevice = this.mainFrame.getSelectedDeviceName();
      if ( selectedDevice != null )
      {
        return getDevice( selectedDevice );
      }
    }

    return null;
  }

  /**
   * Returns all available devices.
   * 
   * @return an array of device names, never <code>null</code>, but an empty
   *         array is possible.
   */
  public String[] getDeviceNames()
  {
    final String[] result;

    synchronized ( this.devices )
    {
      result = new String[this.devices.size()];

      int i = 0;
      for ( Device device : this.devices )
      {
        result[i++] = device.getName();
      }
    }
    // Make sure we've got a predictable order of names...
    Arrays.sort( result );

    return result;
  }

  /**
   * Returns the current diagram settings.
   * 
   * @return the current diagram settings, can be <code>null</code> if there is
   *         no main frame to take the settings from.
   */
  @Deprecated
  public final DiagramSettings getDiagramSettings()
  {
    final Project currentProject = getCurrentProject();
    final UserSettings settings = currentProject.getSettings( MutableDiagramSettings.NAME );
    if ( settings instanceof DiagramSettings )
    {
      return ( DiagramSettings )settings;
    }

    // Overwrite the default created user settings object with our own. This
    // should be done implicitly, so make sure we keep the project's change flag
    // in the correct state...
    final MutableDiagramSettings diagramSettings = new MutableDiagramSettings( settings );

    final boolean oldChangedFlag = currentProject.isChanged();
    try
    {
      currentProject.setSettings( diagramSettings );
    }
    finally
    {
      currentProject.setChanged( oldChangedFlag );
    }

    return diagramSettings;
  }

  /**
   * {@inheritDoc}
   */
  public Exporter getExporter( final String aName ) throws IllegalArgumentException
  {
    synchronized ( this.exporters )
    {
      for ( Exporter exporter : this.exporters )
      {
        if ( aName.equals( exporter.getName() ) )
        {
          return exporter;
        }
      }
    }
    return null;
  }

  /**
   * Returns all available exporters.
   * 
   * @return an array of exporter names, never <code>null</code>, but an empty
   *         array is possible.
   */
  public String[] getExporterNames()
  {
    final String[] result;

    synchronized ( this.exporters )
    {
      result = new String[this.exporters.size()];

      int i = 0;
      for ( Exporter exporter : this.exporters )
      {
        result[i++] = exporter.getName();
      }
    }
    // Make sure we've got a predictable order of names...
    Arrays.sort( result );

    return result;
  }

  /**
   * Returns the "supported" export extensions for the exporter with the given
   * name.
   * 
   * @param aExporterName
   *          the name of the exporter to get the possible file extensions for,
   *          cannot be <code>null</code>.
   * @return an array of supported file extensions, never <code>null</code>.
   */
  public String[] getExportExtensions( final String aExporterName )
  {
    final Exporter exporter = getExporter( aExporterName );
    if ( exporter == null )
    {
      return new String[0];
    }
    return exporter.getFilenameExtentions();
  }

  /**
   * Returns the current host properties.
   * 
   * @return the host properties, can be <code>null</code>.
   */
  public HostProperties getHostProperties()
  {
    return this.hostProperties;
  }

  /**
   * Returns the current project's file name.
   * 
   * @return the project file name, can be <code>null</code> if it is never
   *         saved before.
   */
  public File getProjectFilename()
  {
    return getCurrentProject().getFilename();
  }

  /**
   * Returns the signal diagram controller.
   * 
   * @return a signal diagram controller, never <code>null</code>.
   */
  public SignalDiagramController getSignalDiagramController()
  {
    return this.signalDiagramController;
  }

  /**
   * Returns all available tools.
   * 
   * @return an array of tool names, never <code>null</code>, but an empty array
   *         is possible.
   */
  public String[] getToolNames()
  {
    final String[] result;

    synchronized ( this.tools )
    {
      result = new String[this.tools.size()];

      int i = 0;
      for ( Tool<?> tool : this.tools )
      {
        result[i++] = tool.getName();
      }
    }
    // Make sure we've got a predictable order of names...
    Arrays.sort( result );

    return result;
  }

  /**
   * {@inheritDoc}
   */
  public void gotoTriggerPosition()
  {
    final AcquisitionResult capturedData = getCurrentDataSet().getCapturedData();
    if ( ( capturedData != null ) && capturedData.hasTriggerData() )
    {
      final long position = capturedData.getTriggerPosition();
      this.mainFrame.gotoPosition( 0, position );
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean handleAbout()
  {
    showAboutBox();
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean handlePreferences()
  {
    showPreferencesDialog( getMainFrame() );
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean handleQuit()
  {
    exit();
    // On Mac OS, it appears that if we acknowledge this event, the system
    // shuts down our application for us, thereby not calling our stop/shutdown
    // hooks... By returning false, we're not acknowledging the quit action to
    // the system, but instead do it all on our own...
    return false;
  }

  /**
   * Returns whether or not there's captured data to display.
   * 
   * @return <code>true</code> if there's captured data, <code>false</code>
   *         otherwise.
   */
  public boolean hasCapturedData()
  {
    final DataSet currentDataSet = getCurrentDataSet();
    return ( currentDataSet != null ) && ( currentDataSet.getCapturedData() != null );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean hasPreferences()
  {
    return true;
  }

  /**
   * Runs the tool denoted by the given name.
   * 
   * @param aToolName
   *          the name of the tool to run, cannot be <code>null</code>;
   * @param aParent
   *          the parent window to use, can be <code>null</code>.
   */
  public void invokeTool( final String aToolName, final Window aParent )
  {
    if ( LOG.isLoggable( Level.INFO ) )
    {
      LOG.log( Level.INFO, "Running tool: \"{0}\" ...", aToolName );
    }

    final Tool<?> tool = getTool( aToolName );
    if ( tool == null )
    {
      JOptionPane.showMessageDialog( aParent, "No such tool found: " + aToolName, "Error ...",
          JOptionPane.ERROR_MESSAGE );
    }
    else
    {
      final ToolContext context = createToolContext();
      tool.invoke( aParent, context );
    }

    updateActionsOnEDT();
  }

  /**
   * Returns whether or not a device is selected.
   * 
   * @return <code>true</code> if a device is selected, <code>false</code>
   *         otherwise.
   */
  public boolean isDeviceSelected()
  {
    return ( this.mainFrame != null ) && ( this.mainFrame.getSelectedDeviceName() != null );
  }

  /**
   * {@inheritDoc}
   */
  public boolean isDeviceSetup()
  {
    return isDeviceSelected() && getDevice().isSetup();
  }

  /**
   * Returns whether or not the current project is changed.
   * 
   * @return <code>true</code> if the current project is changed,
   *         <code>false</code> otherwise.
   */
  public boolean isProjectChanged()
  {
    final Project currentProject = getCurrentProject();
    if ( currentProject == null )
    {
      return false;
    }
    return currentProject.isChanged();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onAnnotation( final Annotation<?> aAnnotation )
  {
    final Channel channel = getChannel( aAnnotation.getChannel() );
    channel.addAnnotation( aAnnotation );

    // Accumulate repaint events to avoid an avalanche of events on the EDT...
    this.repaintAccumulatingRunnable.add( ( Void )null );
  }

  /**
   * Opens a given file as OLS-data file.
   * 
   * @param aFile
   *          the file to open, cannot be <code>null</code>.
   * @throws IOException
   *           in case opening/reading from the given file failed.
   */
  public void openDataFile( final File aFile ) throws IOException
  {
    final FileReader reader = new FileReader( aFile );

    try
    {
      getCurrentProject().readData( reader );

      setStatusOnEDT( "Capture data loaded from {0} ...", aFile.getName() );
    }
    finally
    {
      HostUtils.closeResource( reader );

      resetZoomLevel();

      updateActionsOnEDT();
    }
  }

  /**
   * Opens a given file as OLS-project file.
   * 
   * @param aFile
   *          the file to open, cannot be <code>null</code>.
   * @throws IOException
   *           in case opening/reading from the given file failed.
   */
  public void openProjectFile( final File aFile ) throws IOException
  {
    FileInputStream fis = null;

    try
    {
      fis = new FileInputStream( aFile );

      this.projectManager.loadProject( fis );

      final Project project = getCurrentProject();
      project.setFilename( aFile );

      setStatusOnEDT( "Project {0} loaded ...", project.getName() );
    }
    finally
    {
      HostUtils.closeResource( fis );

      resetZoomLevel();

      updateActionsOnEDT();
    }
  }

  /**
   * Removes a given device from this controller.
   * <p>
   * This method is called by the dependency manager.
   * </p>
   * 
   * @param aDevice
   *          the device to remove, cannot be <code>null</code>.
   */
  public void removeDevice( final Device aDevice )
  {
    synchronized ( this.devices )
    {
      this.devices.remove( aDevice );

      final String deviceName = aDevice.getName();

      try
      {
        IManagedAction action = this.actionManager.getAction( SelectDeviceAction.getID( deviceName ) );
        this.actionManager.remove( action );
      }
      catch ( IllegalArgumentException exception )
      {
        LOG.log( Level.FINE, "No action for device {}?!", deviceName );
      }
    }

    updateActionsOnEDT();
  }

  /**
   * Removes a given exporter from this controller.
   * <p>
   * This method is called by the dependency manager.
   * </p>
   * 
   * @param aExporter
   *          the exporter to remove, cannot be <code>null</code>.
   */
  public void removeExporter( final Exporter aExporter )
  {
    synchronized ( this.exporters )
    {
      this.exporters.remove( aExporter );

      final String exporterName = aExporter.getName();

      try
      {
        IManagedAction action = this.actionManager.getAction( ExportAction.getID( exporterName ) );
        this.actionManager.remove( action );
      }
      catch ( IllegalArgumentException exception )
      {
        LOG.log( Level.FINE, "No action for exporter {}?!", exporterName );
      }
    }
  }

  /**
   * Removes the given component provider from this controller, and does this
   * synchronously on the EDT.
   * <p>
   * This method is called by the Dependency Manager.
   * </p>
   * 
   * @param aProvider
   *          the menu component provider, cannot be <code>null</code>.
   */
  public void removeMenu( final ComponentProvider aProvider )
  {
    SwingComponentUtils.invokeOnEDT( new Runnable()
    {
      @Override
      public void run()
      {
        final JMenuBar menuBar = getMainMenuBar();
        if ( menuBar != null )
        {
          aProvider.removedFromContainer();

          menuBar.remove( aProvider.getComponent() );

          menuBar.revalidate();
          menuBar.repaint();
        }
      }
    } );
  }

  /**
   * Removes a given tool from this controller.
   * <p>
   * This method is called by the dependency manager.
   * </p>
   * 
   * @param aTool
   *          the tool to remove, cannot be <code>null</code>.
   */
  public void removeTool( final Tool<?> aTool )
  {
    synchronized ( this.tools )
    {
      this.tools.remove( aTool );

      final String toolName = aTool.getName();

      try
      {
        IManagedAction action = this.actionManager.getAction( RunToolAction.getID( toolName ) );
        this.actionManager.remove( action );
      }
      catch ( IllegalArgumentException exception )
      {
        LOG.log( Level.FINE, "No action for tool {}?!", toolName );
      }
    }
  }

  /**
   * Restarts a new acquisition with the current device and with its current
   * settings.
   */
  public void repeatCaptureData()
  {
    final DataAcquisitionService acquisitionService = getDataAcquisitionService();
    final Device devCtrl = getDevice();

    if ( ( devCtrl == null ) || ( acquisitionService == null ) )
    {
      return;
    }

    try
    {
      setStatusOnEDT( "Capture from {0} started at {1,date,medium} {1,time,medium} ...", devCtrl.getName(), new Date() );

      acquisitionService.acquireData( devCtrl );
    }
    catch ( final IOException exception )
    {
      setStatusOnEDT( "I/O problem: " + exception.getMessage() );

      exception.printStackTrace();

      // Make sure to handle IO-interrupted exceptions properly!
      HostUtils.handleInterruptedException( exception );
    }
    finally
    {
      updateActionsOnEDT();
    }
  }

  /**
   * Stores the current acquisition data to the given file, in the OLS-data file
   * format.
   * 
   * @param aFile
   *          the file to write the data to, cannot be <code>null</code>.
   * @throws IOException
   *           in case of errors during opening/writing to the file.
   */
  public void saveDataFile( final File aFile ) throws IOException
  {
    final FileWriter writer = new FileWriter( aFile );
    try
    {
      getCurrentProject().writeData( writer );

      setStatusOnEDT( "Capture data saved to {0} ...", aFile.getName() );
    }
    finally
    {
      HostUtils.closeResource( writer );
    }
  }

  /**
   * Stores the current acquisition data to the given file, in the OLS-project
   * file format.
   * 
   * @param aName
   *          the name of the project to store, cannot be <code>null</code>;
   * @param aFile
   *          the file to write the data to, cannot be <code>null</code>.
   * @throws IOException
   *           in case of errors during opening/writing to the file.
   */
  public void saveProjectFile( final String aName, final File aFile ) throws IOException
  {
    FileOutputStream out = null;
    try
    {
      final Project project = getCurrentProject();
      project.setFilename( aFile );
      project.setName( aName );

      out = new FileOutputStream( aFile );
      this.projectManager.saveProject( out );

      setStatusOnEDT( "Project {0} saved ...", aName );
    }
    finally
    {
      HostUtils.closeResource( out );
    }
  }

  /**
   * Selects the device with the given name.
   * 
   * @param aDeviceName
   *          the name of the device to select, can be <code>null</code> to
   *          deselect the current selected device.
   */
  public void selectDevice( final String aDeviceName )
  {
    if ( this.mainFrame != null )
    {
      this.mainFrame.setSelectedDeviceName( aDeviceName );
    }
    // Make sure the action reflect the current situation...
    updateActionsOnEDT();
  }

  /**
   * Shows the "about OLS" dialog on screen. the parent window to use, can be
   * <code>null</code>.
   */
  public void showAboutBox()
  {
    if ( this.mainFrame != null )
    {
      this.mainFrame.showAboutBox();
    }
  }

  /**
   * Shows a dialog with all current bundles.
   * 
   * @param aOwner
   *          the owning window to use, can be <code>null</code>.
   */
  public void showBundlesDialog( final Window aOwner )
  {
    final BundlesDialog dialog = new BundlesDialog( aOwner, this.bundleContext );
    if ( dialog.showDialog() )
    {
      dialog.dispose();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Deprecated
  public void showDiagramModeSettingsDialog( final Window aParent )
  {
    if ( this.mainFrame != null )
    {
      final ModeSettingsDialog dialog = new ModeSettingsDialog( aParent, getDiagramSettings() );
      if ( dialog.showDialog() )
      {
        final DiagramSettings settings = dialog.getDiagramSettings();
        getCurrentProject().setSettings( settings );
        diagramSettingsUpdated();
      }

      dialog.dispose();
    }
  }

  /**
   * Shows the global preferences dialog.
   * 
   * @param aParent
   *          the parent window of the dialog, can be <code>null</code>.
   */
  public void showPreferencesDialog( final Window aParent )
  {
    final GeneralSettingsDialog dialog = new GeneralSettingsDialog( aParent, getDiagramSettings() );
    if ( dialog.showDialog() )
    {
      final DiagramSettings settings = dialog.getDiagramSettings();
      getCurrentProject().setSettings( settings );
      diagramSettingsUpdated();
    }

    dialog.dispose();
  }

  /**
   * Called by the dependency manager when this component is about to be
   * started.
   */
  public final void start()
  {
    final HostProperties hostProperties = getHostProperties();

    initOSSpecifics( hostProperties.getShortName() );

    // Make sure we're running on the EDT to ensure the Swing threading model is
    // correctly defined...
    SwingUtilities.invokeLater( new Runnable()
    {
      @Override
      public void run()
      {
        // Cause exceptions to be shown in a more user-friendly way...
        JErrorDialog.installSwingExceptionHandler();

        final MainFrame mf = new MainFrame( ClientController.this );
        setMainFrame( mf );

        mf.setTitle( hostProperties.getFullName() );
        mf.setStatus( "{0} v{1} ready ...", hostProperties.getShortName(), hostProperties.getVersion() );
        mf.setVisible( true );

        LOG.info( "Client started ..." );
      }
    } );
  }

  /**
   * Called by the dependency manager when this component is about to be
   * stopped.
   */
  public final void stop()
  {
    // Make sure we're running on the EDT to ensure the Swing threading model is
    // correctly defined...
    SwingUtilities.invokeLater( new Runnable()
    {
      @Override
      public void run()
      {
        final MainFrame mf = getMainFrame();
        if ( mf != null )
        {
          // Safety guard: also loop through all unclosed frames and close them
          // as well...
          final Window[] openWindows = Window.getWindows();
          for ( Window window : openWindows )
          {
            LOG.log( Level.FINE, "(Forced) closing window {0} ...", window );

            window.setVisible( false );
            window.dispose();
          }
          // release all resources...
          mf.dispose();
          setMainFrame( null );
        }

        JErrorDialog.uninstallSwingExceptionHandler();

        LOG.info( "Client stopped ..." );
      }
    } );
  }

  /**
   * Returns the current data set.
   * 
   * @return the current data set, never <code>null</code>.
   */
  final DataSet getCurrentDataSet()
  {
    final Project currentProject = getCurrentProject();
    if ( currentProject == null )
    {
      return null;
    }
    return currentProject.getDataSet();
  }

  /**
   * Returns the current project.
   * 
   * @return the current project, never <code>null</code>.
   */
  final Project getCurrentProject()
  {
    if ( this.projectManager == null )
    {
      return null;
    }
    return this.projectManager.getCurrentProject();
  }

  /**
   * Returns the current main frame.
   * 
   * @return the main frame, can be <code>null</code>.
   */
  final MainFrame getMainFrame()
  {
    return this.mainFrame;
  }

  /**
   * Returns the main menu bar.
   * 
   * @return the main menu bar, can be <code>null</code>.
   */
  final JMenuBar getMainMenuBar()
  {
    JMenuBar result = null;
    if ( this.mainFrame != null )
    {
      result = this.mainFrame.getJMenuBar();
    }
    return result;
  }

  /**
   * Called by the dependency manager when the project manager service is going
   * away.
   * 
   * @param aProjectManager
   *          the old project manager to remove.
   */
  final void removeProjectManager( final ProjectManager aProjectManager )
  {
    setProjectManager( null );
  }

  /**
   * @param aMainFrame
   *          the main frame to set, cannot be <code>null</code>.
   */
  final void setMainFrame( final MainFrame aMainFrame )
  {
    if ( ( this.mainFrame != null ) && ( this.projectManager != null ) )
    {
      this.projectManager.addPropertyChangeListener( this.mainFrame );
    }

    this.mainFrame = aMainFrame;

    if ( this.projectManager != null )
    {
      this.projectManager.addPropertyChangeListener( this.mainFrame );
    }
  }

  /**
   * Updates the progress on the EventDispatchThread (EDT).
   */
  final void setProgressOnEDT( final int aPercentage )
  {
    if ( this.mainFrame != null )
    {
      SwingComponentUtils.invokeOnEDT( new Runnable()
      {
        @Override
        public void run()
        {
          ClientController.this.mainFrame.setProgress( aPercentage );
        }
      } );
    }
  }

  /**
   * Called by the dependency manager when a (new) project manager service
   * becomes available.
   * 
   * @param aProjectManager
   *          the projectManager to set.
   */
  final void setProjectManager( final ProjectManager aProjectManager )
  {
    if ( ( this.projectManager != null ) && ( this.signalDiagramController != null ) )
    {
      this.projectManager.removePropertyChangeListener( this.signalDiagramController );
    }

    this.projectManager = aProjectManager;

    if ( this.projectManager != null )
    {
      this.projectManager.addPropertyChangeListener( this.signalDiagramController );
    }
  }

  /**
   * Sets the given message + arguments as status message.
   * 
   * @param aMessage
   *          the message to set;
   * @param aMessageArgs
   *          the (optional) arguments of the message.
   */
  void setStatusOnEDT( final String aMessage, final Object... aMessageArgs )
  {
    if ( this.mainFrame != null )
    {
      SwingComponentUtils.invokeOnEDT( new Runnable()
      {
        @Override
        public void run()
        {
          ClientController.this.mainFrame.setStatus( aMessage, aMessageArgs );
        }
      } );
    }
  }

  /**
   * Updates the actions on the EventDispatchThread (EDT).
   */
  final void updateActionsOnEDT()
  {
    SwingComponentUtils.invokeOnEDT( new Runnable()
    {
      @Override
      public void run()
      {
        final DataAcquisitionService acquisitionService = getDataAcquisitionService();
        final Device device = getDevice();

        final boolean deviceControllerSet = ( device != null );
        final boolean deviceCapturing = ( acquisitionService != null ) && acquisitionService.isAcquiring();
        final boolean deviceSetup = deviceControllerSet && !deviceCapturing && device.isSetup();

        getAction( CaptureAction.ID ).setEnabled( deviceControllerSet );
        getAction( CancelCaptureAction.ID ).setEnabled( deviceCapturing );
        getAction( RepeatCaptureAction.ID ).setEnabled( deviceSetup );

        final boolean projectChanged = isProjectChanged();
        final boolean projectSavedBefore = !isAnonymousProject();
        final boolean dataAvailable = hasCapturedData();
        final boolean triggerEnable = dataAvailable && hasTriggerData();
        final boolean cursorsEnabled = areCursorsEnabled();
        final boolean enableCursors = dataAvailable && cursorsEnabled;

        getAction( SaveProjectAction.ID ).setEnabled( projectChanged );
        getAction( SaveProjectAsAction.ID ).setEnabled( projectSavedBefore && projectChanged );
        getAction( SaveDataFileAction.ID ).setEnabled( dataAvailable );

        getAction( GotoTriggerAction.ID ).setEnabled( triggerEnable );

        // Update the cursor actions accordingly...
        getAction( SetCursorModeAction.ID ).setEnabled( dataAvailable );
        getAction( SetCursorModeAction.ID ).putValue( Action.SELECTED_KEY, Boolean.valueOf( cursorsEnabled ) );

        boolean anyCursorSet = false;
        for ( int c = 0; c < Ols.MAX_CURSORS; c++ )
        {
          final boolean cursorPositionSet = isCursorSet( c );
          anyCursorSet |= cursorPositionSet;

          final boolean gotoCursorNEnabled = enableCursors && cursorPositionSet;
          getAction( GotoNthCursorAction.getID( c ) ).setEnabled( gotoCursorNEnabled );
        }

        getAction( GotoFirstCursorAction.ID ).setEnabled( enableCursors && anyCursorSet );
        getAction( GotoLastCursorAction.ID ).setEnabled( enableCursors && anyCursorSet );

        getAction( DeleteAllCursorsAction.ID ).setEnabled( enableCursors && anyCursorSet );
        getAction( RemoveAnnotationsAction.ID ).setEnabled( dataAvailable );

        getAction( SetMeasurementModeAction.ID ).setEnabled( dataAvailable );

        // Update the tools...
        final IManagedAction[] toolActions = getActionsByType( RunToolAction.class );
        for ( final IManagedAction toolAction : toolActions )
        {
          toolAction.setEnabled( dataAvailable );
        }

        // Update the exporters...
        final IManagedAction[] exportActions = getActionsByType( ExportAction.class );
        for ( final IManagedAction exportAction : exportActions )
        {
          exportAction.setEnabled( dataAvailable );
        }
      }
    } );
  }

  /**
   * Returns whether or not the cursors are enabled.
   * 
   * @return <code>true</code> if cursors are enabled, <code>false</code>
   *         otherwise.
   */
  protected boolean areCursorsEnabled()
  {
    return this.signalDiagramController.getSignalDiagramModel().isCursorMode();
  }

  /**
   * Returns all actions of a given type.
   * 
   * @param aActionType
   *          the type of action to return, cannot be <code>null</code>.
   * @return an array of requested actions, never <code>null</code>.
   */
  protected IManagedAction[] getActionsByType( final Class<? extends IManagedAction> aActionType )
  {
    if ( this.actionManager == null )
    {
      return new IManagedAction[0];
    }
    return this.actionManager.getActionByType( aActionType );
  }

  /**
   * Returns whether or not there is trigger data available.
   * 
   * @return <code>true</code> if there is trigger data available,
   *         <code>false</code> otherwise.
   */
  protected boolean hasTriggerData()
  {
    final DataSet currentDataSet = getCurrentDataSet();
    if ( ( currentDataSet == null ) || ( currentDataSet.getCapturedData() == null ) )
    {
      return false;
    }
    return currentDataSet.getCapturedData().hasTriggerData();
  }

  /**
   * Returns whether or not the current project is "anonymous", i.e., not yet
   * saved.
   * 
   * @return <code>true</code> if the current project is anonymous,
   *         <code>false</code> otherwise.
   */
  protected boolean isAnonymousProject()
  {
    final Project currentProject = getCurrentProject();
    if ( currentProject == null )
    {
      return false;
    }
    return currentProject.getFilename() == null;
  }

  /**
   * Returns whether or not a cursor with the given index is set.
   * 
   * @param aCursorIdx
   *          the index of the cursor test.
   * @return <code>true</code> if the cursor with the given index is set,
   *         <code>false</code> otherwise.
   */
  protected boolean isCursorSet( final int aCursorIdx )
  {
    final Cursor cursor = getCursor( aCursorIdx );
    if ( cursor == null )
    {
      return false;
    }
    return cursor.isDefined();
  }

  /**
   * Creates the tool context denoting the range of samples that should be
   * analysed by a tool.
   * 
   * @return a tool context, never <code>null</code>.
   */
  private ToolContext createToolContext()
  {
    int startOfDecode = -1;
    int endOfDecode = -1;

    final DataSet dataSet = getCurrentDataSet();
    final AcquisitionResult capturedData = dataSet.getCapturedData();

    final int dataLength = capturedData.getValues().length;
    if ( areCursorsEnabled() )
    {
      if ( isCursorSet( 0 ) )
      {
        final Cursor cursor1 = dataSet.getCursor( 0 );
        startOfDecode = capturedData.getSampleIndex( cursor1.getTimestamp() ) - 1;
      }
      if ( isCursorSet( 1 ) )
      {
        final Cursor cursor2 = dataSet.getCursor( 1 );
        endOfDecode = capturedData.getSampleIndex( cursor2.getTimestamp() ) + 1;
      }
    }
    else
    {
      startOfDecode = 0;
      endOfDecode = dataLength;
    }

    startOfDecode = Math.max( 0, startOfDecode );
    if ( ( endOfDecode < 0 ) || ( endOfDecode >= dataLength ) )
    {
      endOfDecode = dataLength - 1;
    }

    int channels = capturedData.getChannels();
    if ( channels == Ols.NOT_AVAILABLE )
    {
      channels = Ols.MAX_CHANNELS;
    }

    int enabledChannels = capturedData.getEnabledChannels();
    if ( enabledChannels == Ols.NOT_AVAILABLE )
    {
      enabledChannels = NumberUtils.getBitMask( channels );
    }

    return new DefaultToolContext( startOfDecode, endOfDecode, dataSet );
  }

  /**
   * Should be called after the diagram settings are changed. This method will
   * cause the main frame to be updated.
   */
  private void diagramSettingsUpdated()
  {
    if ( this.mainFrame != null )
    {
      this.mainFrame.diagramSettingsUpdated();
      repaintMainFrame();
    }
  }

  /**
   * Returns the {@link Channel} with the given index.
   * 
   * @param aChannelIdx
   *          the index of the channel to return, >= 0.
   * @return a {@link Channel} instance, never <code>null</code>.
   */
  private Channel getChannel( final int aChannelIdx )
  {
    final DataSet currentDataSet = getCurrentDataSet();
    if ( currentDataSet == null )
    {
      return null;
    }
    return currentDataSet.getChannel( aChannelIdx );
  }

  /**
   * Returns the data acquisition service.
   * 
   * @return a data acquisition service, never <code>null</code>.
   */
  private DataAcquisitionService getDataAcquisitionService()
  {
    return this.dataAcquisitionService;
  }

  /**
   * {@inheritDoc}
   */
  private Device getDevice( final String aName ) throws IllegalArgumentException
  {
    synchronized ( this.devices )
    {
      for ( Device device : this.devices )
      {
        if ( aName.equals( device.getName() ) )
        {
          return device;
        }
      }
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  private Tool<?> getTool( final String aName ) throws IllegalArgumentException
  {
    synchronized ( this.tools )
    {
      for ( Tool<?> tool : this.tools )
      {
        if ( aName.equals( tool.getName() ) )
        {
          return tool;
        }
      }
    }
    return null;
  }

  /**
   * Initializes the OS-specific stuff.
   * 
   * @param aApplicationName
   *          the name of the application (when this needs to be passed to the
   *          guest OS);
   * @param aApplicationCallback
   *          the application callback used to report application events on some
   *          platforms (Mac OS), may be <code>null</code>.
   */
  private void initOSSpecifics( final String aApplicationName )
  {
    final HostInfo hostInfo = HostUtils.getHostInfo();
    if ( hostInfo.isMacOS() )
    {
      // Moves the main menu bar to the screen menu bar location...
      System.setProperty( "apple.laf.useScreenMenuBar", "true" );
      System.setProperty( "apple.awt.graphics.EnableQ2DX", "true" );
      System.setProperty( "com.apple.mrj.application.apple.menu.about.name", aApplicationName );
      System.setProperty( "com.apple.mrj.application.growbox.intrudes", "false" );
      System.setProperty( "com.apple.mrj.application.live-resize", "false" );
      System.setProperty( "com.apple.macos.smallTabs", "true" );
      System.setProperty( "apple.eawt.quitStrategy", "CLOSE_ALL_WINDOWS" );

      // Install an additional accelerator (Cmd+W) for closing option panes...
      ActionMap map = ( ActionMap )UIManager.get( "OptionPane.actionMap" );
      if ( map == null )
      {
        map = new ActionMap();
        UIManager.put( "OptionPane.actionMap", map );
      }
      map.put( "close", new CloseOptionPaneAction() );

      UIManager.put( "OptionPane.windowBindings", //
          new Object[] { SwingComponentUtils.createMenuKeyMask( KeyEvent.VK_W ), "close", "ESCAPE", "close" } );
    }
    else if ( hostInfo.isUnix() )
    {
      try
      {
        UIManager.put( "Application.useSystemFontSettings", Boolean.FALSE );
        setLookAndFeel( "com.jgoodies.looks.plastic.Plastic3DLookAndFeel" );
      }
      catch ( Exception exception )
      {
        Logger.getAnonymousLogger().log( Level.WARNING, "Failed to set look and feel!", exception );
      }
    }
    else if ( hostInfo.isWindows() )
    {
      try
      {
        UIManager.put( "Application.useSystemFontSettings", Boolean.TRUE );
        setLookAndFeel( "com.jgoodies.looks.plastic.PlasticXPLookAndFeel" );
      }
      catch ( Exception exception )
      {
        Logger.getAnonymousLogger().log( Level.WARNING, "Failed to set look and feel!", exception );
      }
    }
  }

  /**
   * Dispatches a request to repaint the entire main frame.
   */
  private void repaintMainFrame()
  {
    if ( this.mainFrame != null )
    {
      SwingComponentUtils.invokeOnEDT( new Runnable()
      {
        @Override
        public void run()
        {
          ClientController.this.mainFrame.repaint();
        }
      } );
    }
  }

  /**
   * Resets the zoom-level to show everything in one screen.
   */
  private void resetZoomLevel()
  {
    this.mainFrame.resetZoomLevel();
  }

  /**
   * Restores the zoom-level to the last zoom-level.
   */
  private void restoreZoomLevel()
  {
    this.mainFrame.restoreZoomLevel();
  }

  /**
   * @param aLookAndFeelClass
   */
  private void setLookAndFeel( final String aLookAndFeelClassName )
  {
    final UIDefaults defaults = UIManager.getDefaults();
    // to make sure we always use system class loader
    defaults.put( "ClassLoader", new CLValue() );

    final ClassLoader oldCL = Thread.currentThread().getContextClassLoader();
    try
    {
      Thread.currentThread().setContextClassLoader( Activator.class.getClassLoader() );
      UIManager.setLookAndFeel( aLookAndFeelClassName );
    }
    catch ( Exception exception )
    {
      // Make sure to handle IO-interrupted exceptions properly!
      if ( !HostUtils.handleInterruptedException( exception ) )
      {
        Logger.getAnonymousLogger().log( Level.WARNING, "Failed to set look and feel!", exception );
      }
    }
    finally
    {
      Thread.currentThread().setContextClassLoader( oldCL );
    }
  }
}
