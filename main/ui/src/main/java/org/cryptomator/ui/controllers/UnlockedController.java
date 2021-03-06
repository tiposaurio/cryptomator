/*******************************************************************************
 * Copyright (c) 2014 Sebastian Stenzel
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 * 
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 ******************************************************************************/
package org.cryptomator.ui.controllers;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.WeakListChangeListener;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart.Data;
import javafx.scene.chart.XYChart.Series;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import org.cryptomator.crypto.CryptorIOSampling;
import org.cryptomator.ui.MainModule.ControllerFactory;
import org.cryptomator.ui.model.Vault;
import org.cryptomator.ui.util.ActiveWindowStyleSupport;
import org.cryptomator.ui.util.mount.CommandFailedException;

import com.google.inject.Inject;

public class UnlockedController implements Initializable {

	private static final int IO_SAMPLING_STEPS = 100;
	private static final double IO_SAMPLING_INTERVAL = 0.25;
	private final ControllerFactory controllerFactory;
	private final ListChangeListener<String> macWarningsListener = this::macWarningsDidChange;
	private final ListChangeListener<String> weakMacWarningsListener = new WeakListChangeListener<>(macWarningsListener);
	private final AtomicBoolean macWarningsWindowVisible = new AtomicBoolean();
	private LockListener listener;
	private Vault vault;
	private Timeline ioAnimation;

	@FXML
	private Label messageLabel;

	@FXML
	private LineChart<Number, Number> ioGraph;

	@FXML
	private NumberAxis xAxis;

	private ResourceBundle rb;

	@Inject
	public UnlockedController(ControllerFactory controllerFactory) {
		this.controllerFactory = controllerFactory;
	}

	@Override
	public void initialize(URL url, ResourceBundle rb) {
		this.rb = rb;
	}

	@FXML
	private void didClickCloseVault(ActionEvent event) {
		try {
			vault.unmount();
		} catch (CommandFailedException e) {
			messageLabel.setText(rb.getString("unlocked.label.unmountFailed"));
			return;
		}
		vault.getNamesOfResourcesWithInvalidMac().removeListener(weakMacWarningsListener);
		vault.stopServer();
		vault.setUnlocked(false);
		if (listener != null) {
			listener.didLock(this);
		}
	}

	// ****************************************
	// MAC Auth Warnings
	// ****************************************

	private void macWarningsDidChange(ListChangeListener.Change<? extends String> change) {
		if (change.getList().size() > 0) {
			Platform.runLater(this::showMacWarningsWindow);
		}
	}

	private void showMacWarningsWindow() {
		if (macWarningsWindowVisible.getAndSet(true) == false) {
			try {
				final FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/mac_warnings.fxml"), rb);
				loader.setControllerFactory(controllerFactory);

				final Parent root = loader.load();
				final Stage stage = new Stage();
				stage.setTitle(String.format(rb.getString("macWarnings.windowTitle"), vault.getName()));
				stage.setScene(new Scene(root));
				stage.sizeToScene();
				stage.setResizable(false);
				stage.setOnHidden(this::onHideMacWarningsWindow);
				ActiveWindowStyleSupport.startObservingFocus(stage);

				final MacWarningsController ctrl = loader.getController();
				ctrl.setVault(vault);
				ctrl.setStage(stage);

				stage.show();
			} catch (IOException e) {
				throw new IllegalStateException("Failed to load fxml file.", e);
			}
		}
	}

	private void onHideMacWarningsWindow(WindowEvent event) {
		macWarningsWindowVisible.set(false);
	}

	// ****************************************
	// IO Graph
	// ****************************************

	private void startIoSampling(final CryptorIOSampling sampler) {
		final Series<Number, Number> decryptedBytes = new Series<>();
		decryptedBytes.setName("decrypted");
		final Series<Number, Number> encryptedBytes = new Series<>();
		encryptedBytes.setName("encrypted");

		ioGraph.getData().add(decryptedBytes);
		ioGraph.getData().add(encryptedBytes);

		ioAnimation = new Timeline();
		ioAnimation.getKeyFrames().add(new KeyFrame(Duration.seconds(IO_SAMPLING_INTERVAL), new IoSamplingAnimationHandler(sampler, decryptedBytes, encryptedBytes)));
		ioAnimation.setCycleCount(Animation.INDEFINITE);
		ioAnimation.play();
	}

	private class IoSamplingAnimationHandler implements EventHandler<ActionEvent> {

		private static final double BYTES_TO_MEGABYTES_FACTOR = 1.0 / IO_SAMPLING_INTERVAL / 1024.0 / 1024.0;
		private final CryptorIOSampling sampler;
		private final Series<Number, Number> decryptedBytes;
		private final Series<Number, Number> encryptedBytes;
		private int step = 0;

		public IoSamplingAnimationHandler(CryptorIOSampling sampler, Series<Number, Number> decryptedBytes, Series<Number, Number> encryptedBytes) {
			this.sampler = sampler;
			this.decryptedBytes = decryptedBytes;
			this.encryptedBytes = encryptedBytes;
		}

		@Override
		public void handle(ActionEvent event) {
			step++;

			final double decryptedMb = sampler.pollDecryptedBytes(true) * BYTES_TO_MEGABYTES_FACTOR;
			decryptedBytes.getData().add(new Data<Number, Number>(step, decryptedMb));
			if (decryptedBytes.getData().size() > IO_SAMPLING_STEPS) {
				decryptedBytes.getData().remove(0);
			}

			final double encrypteddMb = sampler.pollEncryptedBytes(true) * BYTES_TO_MEGABYTES_FACTOR;
			encryptedBytes.getData().add(new Data<Number, Number>(step, encrypteddMb));
			if (encryptedBytes.getData().size() > IO_SAMPLING_STEPS) {
				encryptedBytes.getData().remove(0);
			}

			xAxis.setLowerBound(step - IO_SAMPLING_STEPS);
			xAxis.setUpperBound(step);
		}
	}

	/* Getter/Setter */

	public Vault getVault() {
		return vault;
	}

	public void setVault(Vault vault) {
		this.vault = vault;
		vault.getNamesOfResourcesWithInvalidMac().addListener(weakMacWarningsListener);

		if (vault.getCryptor() instanceof CryptorIOSampling) {
			startIoSampling((CryptorIOSampling) vault.getCryptor());
		} else {
			ioGraph.setVisible(false);
		}
	}

	public LockListener getListener() {
		return listener;
	}

	public void setListener(LockListener listener) {
		this.listener = listener;
	}

	/* callback */

	interface LockListener {
		void didLock(UnlockedController ctrl);
	}

}
