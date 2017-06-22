/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package reversey.backy;

import javafx.scene.image.Image;
import javafx.fxml.FXML;
import javafx.scene.input.*;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import java.util.*;
import java.net.*;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.ListProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

/**
 *
 * @author Caitlin
 */
public class FXMLDocumentController implements Initializable {

    private MachineState currState;

    @FXML
    private TextField instrText;
    @FXML
    private ListView<x86Instruction> instrList;
    @FXML
    private MenuButton insertMenu;
    @FXML
    private HBox buttonHBox;
    @FXML
    private Button nextInstr;
    @FXML
    private Button skipToEnd;
    @FXML
    private Button prevInstr;
    @FXML
    private Button skipToStart;
    @FXML
    private Button currInstr;
    @FXML
    private TableView stackTable;
    @FXML
    private TableView promRegTable;
    @FXML
    private TableColumn stackAddress;
    @FXML
    private TableColumn stackVal;
    @FXML
    private TableColumn stackOrigin;
    @FXML
    private TableColumn registerName;
    @FXML
    private TableColumn registerVal;
    @FXML
    private GridPane entireWindow;
    ObservableList<Register> registerTableList;

    @Override
    public void initialize(URL foo, ResourceBundle bar) {
        instrList.setMouseTransparent(true);
        instrList.setFocusTraversable(false);

        currState = new MachineState();
        ArrayList<String> regHistory = new ArrayList<String>();

        Comparator<Register> regComp = (Register r1, Register r2) -> {
            if (r1.getProminence() > r2.getProminence()) {
                return -1;
            } else if (r1.getProminence() == r2.getProminence()) {
                return 0;
            } else {
                return 1;
            }
        };

        nextInstr.setOnAction((event) -> {
            System.out.println(instrList.getSelectionModel().getSelectedItem());
            this.currState = instrList.getSelectionModel().getSelectedItem().eval(this.currState);
            System.out.println(currState);

            instrList.getSelectionModel().selectNext();
            regHistory.addAll(instrList.getSelectionModel().getSelectedItem().getUsedRegisters());

            registerTableList = FXCollections.observableArrayList(currState.getRegisters(regHistory));
            SortedList<Register> regSortedList = registerTableList.sorted(regComp);
            promRegTable.setItems(regSortedList);
        });

        skipToEnd.setOnAction((event) -> {
            System.out.println(instrList.getSelectionModel().getSelectedItem());
            instrList.getSelectionModel().selectLast();
        });

        prevInstr.setOnAction((event) -> {
            System.out.println(instrList.getSelectionModel().getSelectedItem());
            instrList.getSelectionModel().selectPrevious();
        });

        skipToStart.setOnAction((event) -> {
            System.out.println(instrList.getSelectionModel().getSelectedItem());
            instrList.getSelectionModel().selectFirst();
        });
 
        instrText.setOnKeyPressed(new EventHandler<KeyEvent>() {
            @Override
            public void handle(KeyEvent keyEvent) {
                if (keyEvent.getCode() == KeyCode.ENTER) {
                    String text = instrText.getText();

                    x86Instruction x = x86Instruction.parseInstruction(text);

                    //Enter text in listView
                    instrList.getItems().add(x);
                    if (instrList.getItems().size() == 1) {
                        regHistory.addAll(x.getUsedRegisters());
                        instrList.getSelectionModel().select(0);

                        registerTableList = FXCollections.observableArrayList(currState.getRegisters(regHistory));
                        SortedList<Register> regSortedList = registerTableList.sorted(regComp);
                        promRegTable.setItems(regSortedList);
                    }
                    instrText.clear();
                }
            }
        });
 
        registerTableList = FXCollections.observableArrayList(currState.getRegisters(regHistory));

        SortedList<Register> regSortedList = registerTableList.sorted(regComp);
        promRegTable.setItems(regSortedList);

        registerName.setCellValueFactory(new PropertyValueFactory("name"));
        registerVal.setCellValueFactory(new PropertyValueFactory("value"));

        //TODO: if user wants to change where the instruction should be inserted
        MenuItem beginning = insertMenu.getItems().get(0);
        MenuItem current = insertMenu.getItems().get(1);
        //MenuItem current
        beginning.setText("At beginning");
        current.setText("At current");

        Image skipStartImg = new Image(getClass().getResourceAsStream("skipToStart.png"));
        Image prevInstrImg = new Image(getClass().getResourceAsStream("prevInstr.png"));
        Image currInstrImg = new Image(getClass().getResourceAsStream("currInstr.png"));
        Image nextInstrImg = new Image(getClass().getResourceAsStream("nextInstr.png"));
        Image skipEndImg = new Image(getClass().getResourceAsStream("skipToEnd.png"));

        ImageView skipToStartImgVw = new ImageView(skipStartImg);
        ImageView prevInstrImgVw = new ImageView(prevInstrImg);
        ImageView currInstrImgVw = new ImageView(currInstrImg);
        ImageView nextInstrImgVw = new ImageView(nextInstrImg);
        ImageView skipToEndImgVw = new ImageView(skipEndImg);

        skipToStartImgVw.setFitHeight(35);
        skipToStartImgVw.setFitWidth(35);
        prevInstrImgVw.setFitHeight(35);
        prevInstrImgVw.setFitWidth(35);
        currInstrImgVw.setFitHeight(35);
        currInstrImgVw.setFitWidth(35);
        nextInstrImgVw.setFitHeight(35);
        nextInstrImgVw.setFitWidth(35);
        skipToEndImgVw.setFitHeight(35);
        skipToEndImgVw.setFitWidth(35);

        skipToStart.setGraphic(skipToStartImgVw);
        prevInstr.setGraphic(prevInstrImgVw);
        currInstr.setGraphic(currInstrImgVw);
        nextInstr.setGraphic(nextInstrImgVw);
        skipToEnd.setGraphic(skipToEndImgVw);

        //TODO: Resizing icons/nodes to pane
        /*
   skipToStartImgVw.fitHeightProperty().bind(skipToStart.heightProperty());
   skipToStartImgVw.fitWidthProperty().bind(skipToStart.widthProperty());
   prevInstrImgVw.fitHeightProperty().bind(prevInstr.heightProperty());
   prevInstrImgVw.fitWidthProperty().bind(prevInstr.widthProperty());
   currInstrImgVw.fitHeightProperty().bind(currInstr.heightProperty());
   currInstrImgVw.fitWidthProperty().bind(currInstr.widthProperty());
   nextInstrImgVw.fitHeightProperty().bind(nextInstr.heightProperty());
   nextInstrImgVw.fitWidthProperty().bind(nextInstr.widthProperty());
   skipToEndImgVw.fitHeightProperty().bind(skipToEnd.heightProperty());
   skipToEndImgVw.fitWidthProperty().bind(skipToEnd.widthProperty());
    
    skipToStart.setMinSize(buttonHBox.getPrefWidth(), buttonHBox.getPrefHeight());
    skipToStart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    prevInstr.setMinSize(buttonHBox.getPrefWidth(), buttonHBox.getPrefHeight());
    prevInstr.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    currInstr.setMinSize(buttonHBox.getPrefWidth(), buttonHBox.getPrefHeight());
    currInstr.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    nextInstr.setMinSize(buttonHBox.getPrefWidth(), buttonHBox.getPrefHeight());
    nextInstr.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    skipToEnd.setMinSize(buttonHBox.getPrefWidth(), buttonHBox.getPrefHeight());
    skipToEnd.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
         */
        beginning.setOnAction((event) -> {
            System.out.println("Option 1 selected");
        });

        current.setOnAction((event) -> {
            System.out.println("Option 2 selected");
        });

        //Highlighting selected instruction is newly added item has an index of N
        //instrList.getSelectionModel().getSelectedItem();
        //instrList.getFocusModel().focus(N);
        //Highlighting scrolled then selected
        //instrList.scrollTo(N);
        Platform.runLater(new Runnable() {

            @Override
            public void run() {
                // instrList.scrollTo(N);
                // instrList.getSelectionModel().select(N);
            }
        });

    }
}
