package wmsplugin;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JSeparator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstreetmap.gui.jmapviewer.JobDispatcher;
import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.DiskAccessAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.dialogs.LayerListDialog;
import org.openstreetmap.josm.gui.dialogs.LayerListPopup;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.io.CacheFiles;
import org.openstreetmap.josm.tools.ImageProvider;

import es.emergya.cliente.constants.LogicConstants;
import es.emergya.ui.base.BasicWindow;
import es.emergya.ui.gis.MapMoveListener;
import es.emergya.ui.gis.MapMoveListener.MapMovedCallback;
import es.emergya.ui.gis.layers.MapViewerLayer;
import es.emergya.ui.plugins.ZoomPerformed;

/**
 * This is a layer that grabs the current screen from an WMS server. The data
 * fetched this way is tiled and managerd to the disc to reduce server load.
 * 
 * ==== cambios para 3E ======
 * * Ahora extienede a MapViewerLayer
 * * mv = mapView; era antes una referencia a Main.map.mapView que no usamos
 * * {@link #downloadAndPaintVisible(Graphics, MapView)} recalcula los ppd cuando hace falta
 * 
 */
public class WMSLayer extends MapViewerLayer {
	static final Log log = LogFactory.getLog(WMSLayer.class);
    protected static final Icon icon =
        new ImageIcon(Toolkit.getDefaultToolkit().createImage(WMSPlugin.class.getResource("/images/wms_small.png")));

    public int messageNum = 5; //limit for messages per layer
    protected MapView mv;
    protected String resolution;
    protected boolean stopAfterPaint = false;
    protected int ImageSize = 250;
    protected int dax = 10;
    protected int day = 10;
    protected int minZoom = 3;
    protected double dx = 0.0;
    protected double dy = 0.0;
    protected double pixelPerDegree;
    protected GeorefImage[][] images = new GeorefImage[dax][day];
    JCheckBoxMenuItem startstop = new JCheckBoxMenuItem(tr("Automatic downloading"), true);
    protected JCheckBoxMenuItem alphaChannel = new JCheckBoxMenuItem(new ToggleAlphaAction());
    protected String baseURL;
    protected String cookies;
    protected final int serializeFormatVersion = 4;
    
    BufferedImage snapShot = null;

    private JobDispatcher executor = null;

    public WMSLayer(MapView mapView) {
        this(tr("Blank Layer"), null, null, mapView);
        initializeImages();
    }

    public WMSLayer(String name, String baseURL, String cookies, MapView mapView) {
        super(name);
        WMSPlugin.cache.customCleanUp(CacheFiles.CLEAN_BY_DATE, 0);
        alphaChannel.setSelected(true);
//        background = true; /* set global background variable */
        initializeImages();
        this.baseURL = baseURL;
        this.cookies = cookies;
        WMSGrabber.getProjection(baseURL, true);
        mv = mapView;
        
        ((MapMoveListener)mv).addCallback(new MapMovedCallback() {			
			@Override
			public void action() {
				resolution = scale();
				getPPD();
				executor.cancelOutstandingJobs();
			}
		});
        ((ZoomPerformed)mv).addCallback(new ZoomPerformed.ZoomCallback() {			
			@Override
			public void action() {
				resolution = scale();
				getPPD();
				executor.cancelOutstandingJobs();
			}
		});
        getPPD();

        executor = JobDispatcher.getInstance();
    }

    @Override
    public void destroy() {
        try {
//            executor.shutdown();
            // Might not be initalized, so catch NullPointer as well
        } catch(Exception x) {}
    }

    public void getPPD(){
//    	ImageSize = (int)Math.ceil(Math.max(mv.getWidth(), mv.getHeight()) / Math.min(dax, day)) * 2;
//    	System.err.println("size: " + ImageSize);
//    	
        pixelPerDegree = mv.getWidth() / (bounds().max.lon() - bounds().min.lon());
        WMSPlugin.cache.customCleanUp(WMSPlugin.cache.CLEAN_SMALL_FILES, 2048);
        if(mv.getGraphicsConfiguration() != null) {
			snapShot = mv.getGraphicsConfiguration().createCompatibleImage(
					mv.getWidth(), mv.getHeight(), Transparency.TRANSLUCENT);
        }
    }

    public void initializeImages() {
        images = new GeorefImage[dax][day];
        for(int x = 0; x<dax; ++x)
            for(int y = 0; y<day; ++y)
                images[x][y]= new GeorefImage(false);
    }

    @Override public Icon getIcon() {
        return LogicConstants.getIcon(name);
    }

    public String scale(){
        LatLon ll1 = mv.getLatLon(0,0);
        LatLon ll2 = mv.getLatLon(100,0);
        double dist = ll1.greatCircleDistance(ll2);
        return dist > 1000 ? (Math.round(dist/100)/10.0)+" km" : Math.round(dist*10)/10+" m";
    }

    @Override public String getToolTipText() {
        if(startstop.isSelected())
            return tr("WMS layer ({0}), automatically downloading in zoom {1}", name, resolution);
        else
            return tr("WMS layer ({0}), downloading in zoom {1}", name, resolution);
    }

    @Override public boolean isMergable(Layer other) {
        return false;
    }

    @Override public void mergeFrom(Layer from) {
    }

    private Bounds XYtoBounds (int x, int y) {
        return new Bounds(
            new LatLon(      x * ImageSize / pixelPerDegree,       y * ImageSize / pixelPerDegree),
            new LatLon((x + 1) * ImageSize / pixelPerDegree, (y + 1) * ImageSize / pixelPerDegree));
    }

    private int modulo (int a, int b) {
        return a % b >= 0 ? a%b : a%b+b;
    }

    protected Bounds bounds(){
        return new Bounds(
            mv.getLatLon(0, mv.getHeight()),
            mv.getLatLon(mv.getWidth(), 0));
    }

    @Override
//    public void paint(Graphics g, final MapView mv) {
//    	g.setColor(Color.WHITE);
//    	g.drawString(name, 300, 300);
//    }
    public void paint(Graphics g, final MapView mv) {
//    	super.paint(g, mv);
        if(baseURL == null  || snapShot == null) return;
        snapShot.createGraphics().clearRect(0, 0, snapShot.getWidth(), snapShot.getHeight());
        for(int x = 0; x<dax; ++x)
        	for(int y = 0; y<day; ++y)
        		images[modulo(x,dax)][modulo(y,day)].paint(snapShot.createGraphics(), mv, dx, dy);
        	
		LogicConstants.getIcon("hourglass").paintIcon(mv,
				snapShot.createGraphics(),
				(snapShot.getWidth() - LogicConstants.getIcon("hourglass").getIconWidth()) / 2,
				(snapShot.getHeight() - LogicConstants.getIcon("hourglass").getIconHeight()) / 2);
        
        if((pixelPerDegree / (mv.getWidth() / (bounds().max.lon() - bounds().min.lon())) > minZoom) ){ //don't download when it's too outzoomed
            for(int x = 0; x<dax; ++x)
                for(int y = 0; y<day; ++y)
                    images[modulo(x,dax)][modulo(y,day)].paint(g, mv, dx, dy);
        } else
            downloadAndPaintVisible(g, mv);
        	
    }

    public void displace(double dx, double dy) {
        this.dx += dx;
        this.dy += dy;
    }

    protected void downloadAndPaintVisible(Graphics g, final MapView mv){
        int bminx= (int)Math.floor ((bounds().min.lat() * pixelPerDegree ) / ImageSize );
        int bminy= (int)Math.floor ((bounds().min.lon() * pixelPerDegree ) / ImageSize );
        int bmaxx= (int)Math.ceil  ((bounds().max.lat() * pixelPerDegree ) / ImageSize );
        int bmaxy= (int)Math.ceil  ((bounds().max.lon() * pixelPerDegree ) / ImageSize );
        
//        g.drawImage(snapShot, 0, 0, mv.getWidth(), mv.getHeight(), mv);
        
        if((bmaxx - bminx > dax) || (bmaxy - bminy > day)){
			JOptionPane.showMessageDialog(
							BasicWindow.getFrame(),
							tr("The requested area is too big. Please zoom in a little, or change resolution"));
            return;
        }
        
        for(int x = bminx; x<bmaxx; ++x)
            for(int y = bminy; y<bmaxy; ++y){
                GeorefImage img = images[modulo(x,dax)][modulo(y,day)];
                g.drawRect(x, y, dax, bminy);
				boolean painted = img.paint(g, mv, dx, dy),
						downloading = img.downloadingStarted;
//                System.out.println("painted:" + painted + " downloading:" + downloading);
                if(!painted && !downloading){
                    img.downloadingStarted = true;
                    img.image = null;
                    img.flushedResizedCachedInstance();
                    Grabber gr = WMSPlugin.getGrabber(XYtoBounds(x,y), img, mv, this);
//					executor.submit(gr);
					executor.addJob(gr);
                }
            }
    }

    @Override public void visitBoundingBox(BoundingXYVisitor v) {
        for(int x = 0; x<dax; ++x)
            for(int y = 0; y<day; ++y)
                if(images[x][y].image!=null){
                    v.visit(images[x][y].min);
                    v.visit(images[x][y].max);
                }
    }

    @Override public Object getInfoComponent() {
        return getToolTipText();
    }

    @Override public Component[] getMenuEntries() {
        return new Component[]{
                new JMenuItem(new LayerListDialog.ShowHideLayerAction(this)),
                new JMenuItem(new LayerListDialog.DeleteLayerAction(this)),
                new JSeparator(),
                new JMenuItem(new LoadWmsAction()),
                new JMenuItem(new SaveWmsAction()),
                new JSeparator(),
                startstop,
                alphaChannel,
                new JMenuItem(new changeResolutionAction()),
                new JMenuItem(new reloadErrorTilesAction()),
                new JMenuItem(new downloadAction()),
                new JSeparator(),
                new JMenuItem(new LayerListPopup.InfoAction(this))
        };
    }

    public GeorefImage findImage(EastNorth eastNorth) {
        for(int x = 0; x<dax; ++x)
            for(int y = 0; y<day; ++y)
                if(images[x][y].image!=null && images[x][y].min!=null && images[x][y].max!=null)
                    if(images[x][y].contains(eastNorth, dx, dy))
                        return images[x][y];
        return null;
    }

    public class downloadAction extends AbstractAction {
        public downloadAction() {
            super(tr("Download visible tiles"));
        }
        public void actionPerformed(ActionEvent ev) {
            downloadAndPaintVisible(mv.getGraphics(), mv);
        }
    }

    public class changeResolutionAction extends AbstractAction {
        public changeResolutionAction() {
            super(tr("Change resolution"));
        }
        public void actionPerformed(ActionEvent ev) {
            initializeImages();
            resolution = scale();
            getPPD();
            mv.repaint();
        }
    }

    public class reloadErrorTilesAction extends AbstractAction {
        public reloadErrorTilesAction() {
            super(tr("Reload erroneous tiles"));
        }
        public void actionPerformed(ActionEvent ev) {
            // Delete small files, because they're probably blank tiles.
            // See https://josm.openstreetmap.de/ticket/2307
            WMSPlugin.cache.customCleanUp(WMSPlugin.cache.CLEAN_SMALL_FILES, 2048);

            for (int x = 0; x < dax; ++x) {
                for (int y = 0; y < day; ++y) {
                    GeorefImage img = images[modulo(x,dax)][modulo(y,day)];
                    img.image = null;
                    img.flushedResizedCachedInstance();
                    img.downloadingStarted = false;
                    img.failed = false;
                    mv.repaint();
                }
            }
        }
    }

    public class ToggleAlphaAction extends AbstractAction {
        public ToggleAlphaAction() {
            super(tr("Alpha channel"));
        }
        public void actionPerformed(ActionEvent ev) {
            JCheckBoxMenuItem checkbox = (JCheckBoxMenuItem) ev.getSource();
            boolean alphaChannel = checkbox.isSelected();
            Main.pref.put("wmsplugin.alpha_channel", alphaChannel);

            // clear all resized cached instances and repaint the layer
            for (int x = 0; x < dax; ++x) {
                for (int y = 0; y < day; ++y) {
                    GeorefImage img = images[modulo(x, dax)][modulo(y, day)];
                    img.flushedResizedCachedInstance();
                }
            }
            mv.repaint();
        }
    }

    public class SaveWmsAction extends AbstractAction {
        public SaveWmsAction() {
            super(tr("Save WMS layer to file"), ImageProvider.get("save"));
        }
        public void actionPerformed(ActionEvent ev) {
            File f = DiskAccessAction.createAndOpenSaveFileChooser(tr("Save WMS layer"), ".wms");
            try
            {
                FileOutputStream fos = new FileOutputStream(f);
                ObjectOutputStream oos = new ObjectOutputStream(fos);
                oos.writeInt(serializeFormatVersion);
                oos.writeInt(dax);
                oos.writeInt(day);
                oos.writeInt(ImageSize);
                oos.writeDouble(pixelPerDegree);
                oos.writeObject(name);
                oos.writeObject(baseURL);
                oos.writeObject(images);
                oos.close();
                fos.close();
            }
            catch (Exception ex) {
                ex.printStackTrace(System.out);
            }
        }
    }

    public class LoadWmsAction extends AbstractAction {
        public LoadWmsAction() {
            super(tr("Load WMS layer from file"), ImageProvider.get("load"));
        }
        public void actionPerformed(ActionEvent ev) {
            JFileChooser fc = DiskAccessAction.createAndOpenFileChooser(true,
                    false, tr("Load WMS layer"));
            if(fc == null) return;
            File f = fc.getSelectedFile();
            if (f == null) return;
            try
            {
                FileInputStream fis = new FileInputStream(f);
                ObjectInputStream ois = new ObjectInputStream(fis);
                int sfv = ois.readInt();
                if (sfv != serializeFormatVersion) {
                    JOptionPane.showMessageDialog(Main.parent,
                            tr("Unsupported WMS file version; found {0}, expected {1}", sfv, serializeFormatVersion),
                            tr("File Format Error"),
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
                startstop.setSelected(false);
                dax = ois.readInt();
                day = ois.readInt();
                ImageSize = ois.readInt();
                pixelPerDegree = ois.readDouble();
                name = (String) ois.readObject();
                baseURL = (String) ois.readObject();
                images = (GeorefImage[][])ois.readObject();
                ois.close();
                fis.close();
                mv.repaint();
            }
            catch (Exception ex) {
                log.error("Error al cargar un servicio wms desde un fichero.", ex);
                JOptionPane.showMessageDialog(Main.parent,
                        tr("Error loading file"),
                        tr("Error"),
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
    }
}
