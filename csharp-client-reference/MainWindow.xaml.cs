using System;
using System.Collections.Generic;
using System.Data;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Forms;
using System.Windows.Media;
using System.Windows.Threading;
using EmpowerOps.Volition.DTO;
using Google.Protobuf.Collections;
using Grpc.Core;
using static EmpowerOps.Volition.DTO.NodeStatusCommandOrResponseDTO.Types;
using Application = System.Windows.Application;
using MessageBox = System.Windows.MessageBox;

namespace EmpowerOps.Volition.RefClient
{
    /// <summary>
    /// Interaction logic for MainWindow.xaml
    /// </summary>
    public partial class MainWindow : Window
    {
        private readonly Optimizer.OptimizerClient _client;
        private AsyncServerStreamingCall<OASISQueryDTO> _requests;

        private string _name;    
        private bool _isRegistered = false;
        private bool _isCanceled = false;
        private bool _failMark = false;
        private bool _isOptimizing;
        private readonly BindingSource _inputSource = new BindingSource();
        private readonly BindingSource _outputSource = new BindingSource();
        private CancellationTokenSource _evaluationCancellationTokenSource;

        public MainWindow()
        {
            //https://grpc.io/docs/quickstart/csharp.html#update-the-client
            var channel = new Channel("127.0.0.1:5550", ChannelCredentials.Insecure);
            _client = new Optimizer.OptimizerClient(channel);
            
            InitializeComponent();
            ConfigGrid();
            UpdateButton();
        }

        private void ConfigGrid()
        {
            InputGrid.ItemsSource = _inputSource;
            OutputGrid.ItemsSource = _outputSource;
        }

        private async void StartOptimization_Click(object sender, RoutedEventArgs e)
        {
            var message = new StartOptimizationCommandDTO();
            StartOptimizationResponseDTO response = await _client.startOptimizationAsync(message);
            _isOptimizing = true;
            Log($"got response: Started-{response}");
         
            UpdateButton();
        }

        private async void StopOptimization_Click(object sender, RoutedEventArgs e)
        {
            var message = new StopOptimizationCommandDTO();

            StopOptimizationResponseDTO response = await _client.StopOptimizationAsync(message);
            _isOptimizing = false;
            Log($"got response: Stopped-{response}");
            UpdateButton();
        }

        private void UpdateButton()
        {
            if (_isRegistered)
            {
                RegisterLabel.Content = $"Registered as {_name}";
            }
            else
            {
                RegisterLabel.Content = $"Not registered";
            }
          
            RunStatusLabel.Content = _isOptimizing ? "Status: Running" : "Status: Idle";
            DisplayImage.Source = _isOptimizing 
                ? (ImageSource)this.TryFindResource("Simulation_Running") 
                : (ImageSource)this.TryFindResource("Simulation_Idle");
        }

        private async void Simulation_loop(AsyncServerStreamingCall<OASISQueryDTO> requests)
        {
            //request for inputs, do the simulation, return result, repeat
            var requestsResponseStream = requests.ResponseStream;
            //https://github.com/grpc/grpc.github.io/blob/master/docs/tutorials/basic/csharp.md
           
            while (await requestsResponseStream.MoveNext(new CancellationToken()))
            {
                HandleRequest(requestsResponseStream.Current);
            }
            Unregister();
        }
       
        private void HandleRequest(OASISQueryDTO request)
        {
            try
            {
                switch (request.RequestCase)
                {
                    case OASISQueryDTO.RequestOneofCase.EvaluationRequest:
                    {
                        _evaluationCancellationTokenSource = new CancellationTokenSource();
                        Task.Run(() =>
                        {
                            MapField<string, double> inputs = request.EvaluationRequest.InputVector;
                            Log($"Receive Input: {inputs}");
                            try
                            {
                                var result = Evaluate(inputs);
                                Log($"Send Result: {result}");
                                var simulationResponseDto = new SimulationResponseDTO
                                {
                                    Name = _name,
                                    OutputVector = {result}
                                }; //what is the difference between = {value} vs just = value
                                var simulationResultConfirmDto = _client.offerSimulationResult(simulationResponseDto);
                                Log($"got response: Result-{simulationResultConfirmDto}");
                            }
                            catch (EvaluationException e)
                            {
                                var result = new MapField<string, double>();
                                foreach (Output output in _outputSource)
                                {
                                    var evaluationResult = Double.PositiveInfinity;
                                    result.Add(output.Name, evaluationResult);
                                    output.CurrentValue = evaluationResult;
                                    output.EvaluatingValue = evaluationResult.ToString();
                                    UpdateBindingSourceOnUI(_outputSource);
                                }

                                _client.offerErrorResult(new SimulationErrorResponseDTO
                                {
                                    Name = _name,
                                    Exception = e.ToString(),
                                    OutputVector = {result}
                                });
                            }


                        }, _evaluationCancellationTokenSource.Token);
                        break;
                    }
                    case OASISQueryDTO.RequestOneofCase.NodeStatusRequest:
                        var nodeStatusCommandOrResponseDto = BuildNodeUpdateResponse();
                        var nodeChangeConfirmDto = _client.offerSimulationConfig(nodeStatusCommandOrResponseDto);
                        Log($"get response: Config-{nodeChangeConfirmDto}");
                        break;
                    case OASISQueryDTO.RequestOneofCase.None:
                        break;
                    case OASISQueryDTO.RequestOneofCase.CancelRequest:
                        _isCanceled = true;
                        Log($"get cancel request");
                        break;
                }
            }
            catch (Exception e)
            {
                Log($"Error : {e}");
            }
          
        }

        private MapField<string, double> Evaluate(MapField<string, double> inputs)
        {
            try
            {
                foreach (Input input in _inputSource)
                {
                    input.EvaluatingValue = inputs[input.Name];
                }
                foreach (Output output in _outputSource)
                {
                    output.EvaluatingValue = "Evaluating...";
                }

                UpdateBindingSourceOnUI(_inputSource);
                UpdateBindingSourceOnUI(_outputSource);

                
                Log("Evaluating...");
                Thread.Sleep(2000);
                _evaluationCancellationTokenSource.Token.ThrowIfCancellationRequested();
                if (_failMark)
                {
                    Log($"Error: Error evaluating {inputs}");
                    _failMark = false;
                    throw new EvaluationException($"/Error evaluating {inputs}");
                }
                Thread.Sleep(2000);
                _evaluationCancellationTokenSource.Token.ThrowIfCancellationRequested();

                var result = new MapField<string, double>();
                var random = new Random();
                foreach (Input input in _inputSource)
                {
                    input.CurrentValue = inputs[input.Name];
                }
                UpdateBindingSourceOnUI(_inputSource);

                foreach (Output output in _outputSource)
                {
                    var evaluationResult = random.NextDouble();
                    result.Add(output.Name, evaluationResult);
                    output.CurrentValue = evaluationResult;
                    output.EvaluatingValue = evaluationResult.ToString();
                    UpdateBindingSourceOnUI(_outputSource);
                }
                return result;
            }
            catch (OperationCanceledException)
            {
                Log("Canceled");
                return new MapField<string, double>();
            }
         
        }

        private async void UpdateNode()
        {
            var nodeStatusCommandOrResponseDto = BuildNodeUpdateResponse();
            var updateNodeAsync = await _client.updateNodeAsync(nodeStatusCommandOrResponseDto);
            Log($"get response: Sync-{updateNodeAsync}");
        }

        private NodeStatusCommandOrResponseDTO BuildNodeUpdateResponse()
        {
            var nodeStatusCommandOrResponseDto = new NodeStatusCommandOrResponseDTO
            {
                Name = _name
            };
            //gather inputs/ outputs NodeStatusCommandOrResponseDTO
            foreach (Input input in _inputSource)
            {
                nodeStatusCommandOrResponseDto.Inputs.Add(new PrototypeInputParameter
                {
                    Name = input.Name,
                    LowerBound = input.LowerBound,
                    UpperBound = input.UpperBound
                });
            }

            foreach (Output output in _outputSource)
            {
                nodeStatusCommandOrResponseDto.Outputs.Add(new PrototypeOutputParameter
                {
                    Name = output.Name
                });
            }

            return nodeStatusCommandOrResponseDto;
        }

        private async void Register_Click(object sender, RoutedEventArgs e)
        {
            var registrationCommandDto = new RegistrationCommandDTO {Name = RegName.Text };
            _name = registrationCommandDto.Name;
            _requests = _client.register(registrationCommandDto);
            Log($"got response: {_requests}");
            _isRegistered = true;
            UpdateButton();
            Simulation_loop(_requests);
        }

        private void rename_Button_Click(object sender, RoutedEventArgs e)
        {
            var nodeNameChangeCommandDto = new NodeNameChangeCommandDTO {OldName = _name, NewName = RegName.Text };

            NodeNameChangeResponseDTO nodeNameChangeResponseDto = _client.changeNodeName(nodeNameChangeCommandDto);
            if (nodeNameChangeResponseDto.Changed)
            {
                MessageBox.Show($"Change name {nodeNameChangeCommandDto.OldName} to {nodeNameChangeCommandDto.NewName} succeed.");
                _name = nodeNameChangeCommandDto.NewName;
                RegisterLabel.Content = $"Registered as {_name}";
            }
            else
            {
                MessageBox.Show($"Change name {nodeNameChangeCommandDto.OldName} to {nodeNameChangeCommandDto.NewName} failed.");
            }
        }

        private void Log(string message)
        {
            _client.sendMessageAsync(new MessageCommandDTO() { Name = _name, Message = message });
            Application.Current.Dispatcher.BeginInvoke(
                DispatcherPriority.Background,
                new Action(() =>
                {
                    LogInfoTextBox.Text = LogInfoTextBox.Text += message + "\n";
                    LogInfoTextBox.ScrollToEnd();                  
                }));
            
        }

        private void UpdateBindingSourceOnUI(BindingSource bindingSource)
        {
            Application.Current.Dispatcher.BeginInvoke(
                DispatcherPriority.Background,
                new Action(() =>
                {
                    bindingSource.ResetBindings(false);
                }));
        }

        private void AddInput_Button_Click(object sender, RoutedEventArgs e)
        {
            _inputSource.Add(new Input
            {
                Name = "x1", 
                LowerBound = 0.0,
                UpperBound = 10.0
            });
           
        }

        private void AddOutput_Button_Click(object sender, RoutedEventArgs e)
        {

            _outputSource.Add(new Output
            {
                Name = "f1"
            });

        }

        private void RemoveInput_Button_Click(object sender, RoutedEventArgs e)
        {
            _inputSource.Remove(InputGrid.SelectedItem);
        }

        private void RemoveOutput_Button_Click(object sender, RoutedEventArgs e)
        {
            _outputSource.Remove(OutputGrid.SelectedItem);
        }

        private void Button_Click(object sender, RoutedEventArgs e)
        {
            UpdateNode();
        }

        private void UnRegister_Click(object sender, RoutedEventArgs e)
        {
            if (! _isRegistered)
            {
                MessageBox.Show($"Not yet registered");
                return ;
            }

            Unregister();
        }

        private void Unregister()
        {
            var reponseDto = _client.unregister(new UnRegistrationRequestDTO() {Name = _name});
            var message = reponseDto.Unregistered ? "Successful" : "Failed";
            MessageBox.Show($"Unregistration {message}");
            _name = "";
            _isRegistered = false;
            UpdateButton();
        }


        private void FailNextRun_Click(object sender, RoutedEventArgs e)
        {
            _failMark = true;
        }
    }

    public class Input
    {
        public string Name { get; set; }
        public double LowerBound { get; set; }
        public double UpperBound { get; set; }
        public double CurrentValue { get; set; }
        public double EvaluatingValue { get; set; }
    }

    public class Output
    {
        public string Name { get; set; }
        public double CurrentValue { get; set; }
        public String EvaluatingValue { get; set; }
    }

    public class EvaluationException : Exception {
        public EvaluationException(string message) : base(message)
        {
        }
    }
}
