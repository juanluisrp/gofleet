/*
 * Copyright (C) 2010, Emergya (http://www.emergya.es)
 *
 * @author <a href="mailto:jlrodriguez@emergya.es">Juan Luís Rodríguez</a>
 * @author <a href="mailto:marias@emergya.es">María Arias</a>
 * @author <a href="mailto:fario@emergya.es">Félix del Río Beningno</a>
 *
 * This file is part of GoFleet
 *
 * This software is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * As a special exception, if you link this library with other files to
 * produce an executable, this library does not by itself cause the
 * resulting executable to be covered by the GNU General Public License.
 * This exception does not however invalidate any other reasons why the
 * executable file might be covered by the GNU General Public License.
 */
/*
 * 30/07/2009
 */
package es.emergya.ui.gis;

/**
 * @author fario
 * 
 */
public interface MapMoveListener {
	/**
	 * When the map movenent has just started -- callbacks can be called on the
	 * implementation
	 */
	public void movementStarted();

	/**
	 * When the map movenent has just finished -- callbacks can be called on the
	 * implementation
	 */
	public void movementEnded();

	/**
	 * Adds a callback to this element callback lists
	 * 
	 * @param mmc
	 */
	public void addCallback(MapMovedCallback mmc);

	/**
	 * Removes a callback from the lists
	 * 
	 * @param mmc
	 * @return if that callback was active
	 */
	public boolean removeCallback(MapMovedCallback mmc);

	/**
	 * Clears all callbacks
	 */
	public void clearCallbacks();

	/**
	 * Actions
	 * 
	 * @author fario
	 * 
	 */
	public interface MapMovedCallback {
		public abstract void action();
	}
}
